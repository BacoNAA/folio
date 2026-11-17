package com.baconaa.folio.ui

import android.net.Uri
import com.baconaa.folio.media.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Page { Capture, Records, Search, Detail, Editor }

data class RecordEditor(
    val draftId: DraftId? = null,
    val recordId: RecordId? = null,
    val items: List<ContentItem> = emptyList(),
    val textValues: Map<ItemId, TextFieldValue> = emptyMap(),
    val activeItemId: ItemId? = null,
    val persistedItems: List<ContentItem> = emptyList(),
    val revision: Long = 0,
    val savedRevision: Long = 0,
    val returnPage: Page = Page.Capture,
    val canUndo: Boolean = false,
    val needsReload: Boolean = false,
) {
    val value: TextFieldValue get() = textValues.values.firstOrNull() ?: TextFieldValue()
    val hasContent: Boolean get() = items.any { it.hasContent() }
}

private fun ContentItem.hasContent() = when (this) {
    is ContentItem.Text -> text.isNotBlank()
    is ContentItem.Checklist -> entries.any { it.text.isNotBlank() }
    else -> true
}

data class FolioState(
    val page: Page = Page.Capture,
    val editor: RecordEditor? = null,
    val drafts: List<DraftPreview> = emptyList(),
    val records: List<RecordCard> = emptyList(),
    val timeline: EventTimeline = EventTimeline(emptyList(), emptyList()),
    val recent: Boolean = false,
    val query: String = "",
    val results: List<RecordCard> = emptyList(),
    val detailId: RecordId? = null,
    val detail: RecordContent? = null,
    val detailOrigin: Page = Page.Records,
    val savedId: RecordId? = null,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val saveError: String? = null,
    val recording: Boolean = false,
    val pendingAttachment: PendingAttachment? = null,
    val importFailed: Boolean = false,
)

/** UI 状态由主线程持有；草稿写入串行，完成和离开等待最后一次输入落盘。 */
class FolioViewModel(
    private val drafts: TextDrafts,
    private val published: PublishedTextRecords,
    private val mixed: MixedContentDrafts,
    private val browsing: RecordBrowsing,
    private val media: EditorMedia? = null,
) : ViewModel() {
    var state by mutableStateOf(FolioState())
        private set
    private val writes = Mutex()
    private var loadVersion = 0
    private var pendingInitialId: DraftId? = null

    fun home(page: Page) {
        if (state.busy || state.editor != null) return
        media?.stopPlayback()
        loadVersion++
        state = state.copy(page = page, error = null, loading = false)
        if (page == Page.Records || page == Page.Search) refresh()
    }

    private var recordingAfter: ItemId? = null
    private var failedPhoto: Pair<Uri, ExternalTarget>? = null
    val playback = media?.playback ?: MutableStateFlow(AudioPlaybackState())

    init {
        media?.let { controls -> viewModelScope.launch {
            controls.recording.collect { recording ->
                state = state.copy(recording = recording.isRecording,
                    error = recording.error ?: state.error)
            }
        } }
    }

    fun newText() = begin(ContentItem.Text(ItemId(UUID.randomUUID().toString()), ""))
    fun newChecklist() = begin(ContentItem.Checklist(ItemId(UUID.randomUUID().toString()),
        listOf(ChecklistEntry(ChecklistEntryId.create(), ""))))

    private fun begin(item: ContentItem? = null) {
        if (state.busy || state.editor != null) return
        loadVersion++
        state = state.copy(page = Page.Editor, editor = RecordEditor(
            items = listOfNotNull(item), activeItemId = item?.id,
            textValues = if (item is ContentItem.Text) mapOf(item.id to TextFieldValue()) else emptyMap()),
            savedId = null, error = null, saveError = null, loading = false)
    }

    fun change(value: TextFieldValue) {
        state.editor?.items?.filterIsInstance<ContentItem.Text>()?.firstOrNull()?.let { changeText(it.id, value) }
    }

    fun changeText(id: ItemId, value: TextFieldValue) {
        updateItem(id) { (it as ContentItem.Text).copy(text = value.text) }
        val editor = state.editor ?: return
        if (state.busy || editor.needsReload) return
        state = state.copy(editor = editor.copy(textValues = editor.textValues + (id to value), activeItemId = id))
        save()
    }

    fun changeCaption(id: ItemId, text: String) {
        updateItem(id) { when (it) {
            is ContentItem.Photo -> it.copy(caption = text)
            is ContentItem.Audio -> it.copy(caption = text)
            else -> it
        } }
        save()
    }

    fun changeChecklist(id: ItemId, entries: List<ChecklistEntry>) {
        updateItem(id) { (it as ContentItem.Checklist).copy(entries = entries) }
        save()
    }

    private fun updateItem(id: ItemId, update: (ContentItem) -> ContentItem) {
        val editor = state.editor ?: return
        if (state.busy || editor.needsReload) return
        state = state.copy(editor = editor.copy(items = editor.items.map { if (it.id == id) update(it) else it },
            activeItemId = id, revision = editor.revision + 1), saveError = null)
    }

    fun save() {
        if (state.editor == null) return
        viewModelScope.launch { writes.withLock { saveLatest() } }
    }

    private suspend fun saveLatest(): Boolean {
        while (true) {
            val snapshot = state.editor ?: return true
            if (snapshot.needsReload) return false
            if (snapshot.revision == snapshot.savedRevision) return true
            if (snapshot.draftId == null && !snapshot.hasContent) return true
            try {
                if (snapshot.draftId == null) {
                    // 未落盘时仅有一个输入项；结构操作先等待保存，再交给混排接口。
                    val first = snapshot.items.single()
                    val id = pendingInitialId ?: withContext(Dispatchers.IO) {
                        when (first) {
                            is ContentItem.Text -> requireNotNull(drafts.saveDraft(null,
                                (snapshot.textValues[first.id] ?: TextFieldValue(first.text)).asEdit()))
                            is ContentItem.Checklist -> mixed.appendChecklist(null, first.entries)
                            else -> error("媒体必须先保存原件")
                        }
                    }
                    pendingInitialId = id
                    val content = withContext(Dispatchers.IO) { requireNotNull(mixed.openMixedDraft(id)) }
                    val current = state.editor ?: return true
                    val actualId = content.items.single().id
                    val mapped = current.items.map { it.withId(actualId) }
                    state = state.copy(editor = current.copy(draftId = id, items = mapped,
                        textValues = current.textValues[first.id]?.let { mapOf(actualId to it) } ?: emptyMap(),
                        activeItemId = actualId, persistedItems = content.items, savedRevision = -1), saveError = null)
                    pendingInitialId = null
                } else {
                    withContext(Dispatchers.IO) {
                        val previous = snapshot.persistedItems.associateBy { it.id }
                        // 当前文字项最后保存，后端保留该项及项内光标。
                        val ordered = snapshot.items.sortedBy { it.id == snapshot.activeItemId }
                        for (item in ordered) {
                            if (item == previous[item.id] && !(item is ContentItem.Text && item.id == snapshot.activeItemId)) continue
                            when (item) {
                                is ContentItem.Text -> mixed.editTextItem(snapshot.draftId, item.id,
                                    (snapshot.textValues[item.id] ?: TextFieldValue(item.text)).asEdit())
                                is ContentItem.Photo -> mixed.editPhotoCaption(snapshot.draftId, item.id, item.caption)
                                is ContentItem.Audio -> mixed.editAudioCaption(snapshot.draftId, item.id, item.caption)
                                is ContentItem.Checklist -> mixed.editChecklist(snapshot.draftId, item.id, item.entries)
                            }
                        }
                    }
                    val current = state.editor ?: return true
                    state = state.copy(editor = current.copy(persistedItems = snapshot.items,
                        savedRevision = snapshot.revision), saveError = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                state = state.copy(saveError = "草稿保存失败，请重试。当前输入仍保留在页面中。")
                return false
            }
        }
    }

    fun addText(after: ItemId? = null) = addEmpty(after, false)
    fun addChecklist(after: ItemId? = null) = addEmpty(after, true)

    private fun addEmpty(after: ItemId?, checklist: Boolean) {
        if (state.recording || state.pendingAttachment != null) return
        structure("添加失败，请重试。") { editor ->
            val id = editor.draftId
            if (id == null) {
                val item = if (checklist) ContentItem.Checklist(ItemId(UUID.randomUUID().toString()),
                    listOf(ChecklistEntry(ChecklistEntryId.create(), ""))) else ContentItem.Text(ItemId(UUID.randomUUID().toString()), "")
                state = state.copy(editor = editor.copy(items = listOf(item), activeItemId = item.id,
                    textValues = if (item is ContentItem.Text) mapOf(item.id to TextFieldValue()) else emptyMap(), revision = 0, savedRevision = 0))
            } else withContext(Dispatchers.IO) {
                if (checklist) mixed.insertChecklist(id, after, listOf(ChecklistEntry(ChecklistEntryId.create(), "")))
                else mixed.insertText(id, after, TextEdit("", 0))
            }
        }
    }

    fun move(id: ItemId, direction: MoveDirection) = structure("移动失败，请重试。") {
        it.draftId?.let { draft -> withContext(Dispatchers.IO) { mixed.moveItem(draft, id, direction) } }
    }

    fun remove(id: ItemId) = structure("移除失败，请重试。") {
        media?.stopPlayback()
        if (it.draftId == null) state = state.copy(editor = it.copy(items = emptyList(), textValues = emptyMap(), activeItemId = null))
        else withContext(Dispatchers.IO) { mixed.removeItem(it.draftId, id) }
    }

    fun undo() = structure("撤销失败，请重试。") {
        it.draftId?.let { draft -> withContext(Dispatchers.IO) { mixed.undoRemoval(draft) } }
    }

    private fun structure(failure: String, action: suspend (RecordEditor) -> Unit) {
        if (state.recording || state.pendingAttachment != null || state.importFailed || state.editor?.needsReload == true) return
        runAction(failure) {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                action(editor)
                if (editor.draftId != null) reloadCurrent()
            }
        }
    }

    fun reload() = runAction("草稿读取失败，请重试。") { writes.withLock { reloadCurrent() } }

    private suspend fun reloadCurrent() {
        val editor = state.editor ?: return
        val id = editor.draftId ?: return
        // 结构已提交；重读失败时只重读，不重复插入或覆盖新顺序。
        state = state.copy(editor = editor.copy(needsReload = true))
        try { readEditor(id, editor.recordId, editor.returnPage) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = state.copy(error = "操作已保存，草稿读取失败，请重试读取。") }
    }

    fun prepareExternal(after: ItemId?, launch: (ExternalTarget) -> Unit) {
        if (state.recording || state.pendingAttachment != null || state.editor?.needsReload == true) return
        if (state.editor == null) begin()
        runAction("暂时无法打开媒体入口，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                launch(ExternalTarget(editor.draftId?.value, editor.recordId?.value, after?.value,
                    editor.returnPage, state.detailOrigin))
            }
        }
    }

    private suspend fun restoreTarget(target: ExternalTarget) {
        if (state.editor == null) {
            if (target.draftId != null) readEditor(DraftId(target.draftId), target.recordId?.let(::RecordId), target.returnPage)
            else state = state.copy(page = Page.Editor, editor = RecordEditor(returnPage = target.returnPage))
            state = state.copy(detailOrigin = target.detailOrigin)
        }
        check(state.editor != null && state.editor?.draftId?.value == target.draftId) { "原草稿已不可编辑" }
    }

    fun receivePhoto(uri: Uri, target: ExternalTarget) {
        failedPhoto = uri to target
        runAction("照片保存失败，原有内容仍保留，请使用下方入口重试。") {
            try {
                restoreTarget(target)
                val id = requireNotNull(media).importPhoto(uri)
                state = state.copy(pendingAttachment = PendingAttachment.Photo(id, target.afterId?.let(::ItemId)), importFailed = false)
                failedPhoto = null
                writes.withLock { if (saveLatest()) bindPending() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state = state.copy(importFailed = failedPhoto != null); throw failure }
        }
    }

    fun retryPhoto() { failedPhoto?.let { receivePhoto(it.first, it.second) } }

    fun cancelPhotoImport() {
        if (state.busy || !state.importFailed) return
        failedPhoto = null
        state = state.copy(importFailed = false, error = null)
    }

    fun attachAudio(clip: AudioClip, after: ItemId? = null) {
        if (state.editor == null) begin()
        state = state.copy(pendingAttachment = PendingAttachment.Audio(clip, after))
        retryAttachment()
    }

    fun retryAttachment() = runAction("媒体加入草稿失败，原件已保留，请重试。") {
        writes.withLock { if (saveLatest()) bindPending() }
    }

    private suspend fun bindPending() {
        val pending = state.pendingAttachment ?: return
        val editor = state.editor ?: return
        val id = withContext(Dispatchers.IO) {
            when (pending) {
                is PendingAttachment.Photo -> if (editor.draftId == null) drafts.appendPhotos(null, listOf(pending.id))
                    else { mixed.insertPhoto(editor.draftId, pending.after, pending.id); editor.draftId }
                is PendingAttachment.Audio -> if (editor.draftId == null) mixed.appendAudio(null, pending.clip.id)
                    else { mixed.insertAudio(editor.draftId, pending.after, pending.clip.id); editor.draftId }
            }
        }
        state = state.copy(editor = editor.copy(draftId = id), pendingAttachment = null)
        reloadCurrent()
    }

    fun startRecording(target: ExternalTarget) = runAction("无法开始录音，请检查麦克风权限或是否被占用。") {
        restoreTarget(target)
        recordingAfter = target.afterId?.let(::ItemId)
        requireNotNull(media).start()
    }

    fun stopRecording(afterSave: (() -> Unit)? = null) {
        if (state.busy || !state.recording) return
        try {
            val clip = requireNotNull(media).stop()
            state = state.copy(recording = false, pendingAttachment = PendingAttachment.Audio(clip, recordingAfter))
            runAction("录音加入草稿失败，原件已保留，请重试。", afterSuccess = {
                if (state.pendingAttachment == null && state.editor?.needsReload == false && state.saveError == null) afterSave?.invoke()
            }) {
                writes.withLock { if (saveLatest()) bindPending() }
            }
        } catch (_: Exception) { state = state.copy(recording = false, error = "录音过短、中断或保存失败，请重新录制。此前保存的内容仍保留。") }
    }

    fun cancelRecording() { media?.cancel(); state = state.copy(recording = false) }
    fun permissionDenied() { state = state.copy(error = "未获得麦克风权限，仍可继续文字、照片和清单记录。") }
    fun mediaUnavailable(message: String) { state = state.copy(error = message) }
    fun play(id: AudioId) {
        if (state.recording) return
        if (playback.value.audioId == id && playback.value.isPlaying) media?.pause(id) else media?.play(id)
    }
    fun stopPlayback() { media?.stopPlayback() }
    fun background() { media?.stopPlayback(); if (state.recording) stopRecording() else save() }
    override fun onCleared() { media?.release(); super.onCleared() }

    fun resume(preview: DraftPreview) = loadEditor(preview.id, preview.recordId, state.page)

    fun editRecord() {
        val id = state.detailId ?: return
        if (!canEdit(state.detail)) return
        runAction("暂时无法打开编辑，请重试。") {
            val draftId = withContext(Dispatchers.IO) { drafts.editRecord(id) }
            readEditor(draftId, id, Page.Detail)
        }
    }

    private fun loadEditor(id: DraftId, recordId: RecordId?, origin: Page) {
        runAction("草稿读取失败，请重试。") { readEditor(id, recordId, origin) }
    }

    private suspend fun readEditor(id: DraftId, recordId: RecordId?, origin: Page) {
        val content = withContext(Dispatchers.IO) { mixed.openMixedDraft(id) }
        if (content == null) {
            state = state.copy(error = "这份草稿已不存在，请返回记录页刷新。")
            return
        }
        val canUndo = withContext(Dispatchers.IO) { mixed.canUndoRemoval(id) }
        loadVersion++
        state = state.copy(page = Page.Editor, loading = false, error = null, saveError = null,
            detailOrigin = if (recordId != null && origin != Page.Detail) origin else state.detailOrigin,
            editor = RecordEditor(id, recordId, content.items,
                content.items.filterIsInstance<ContentItem.Text>().associate { item -> item.id to TextFieldValue(item.text,
                    TextRange(if (item.id == content.activeItemId) content.cursor else item.text.length)) },
                content.activeItemId, content.items, returnPage = origin, canUndo = canUndo))
    }

    fun complete() {
        if (state.editor?.hasContent != true || state.recording || state.pendingAttachment != null || state.importFailed) return
        media?.stopPlayback()
        runAction("完成失败，草稿仍保留，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                val id = editor.draftId ?: return@withLock
                val recordId = withContext(Dispatchers.IO) { mixed.completeMixedDraft(id) }
                state = state.copy(editor = null, saveError = null, savedId = recordId, page = Page.Capture)
                if (editor.recordId != null) showDetail(recordId, state.detailOrigin)
            }
        }
    }

    fun leaveEditor() {
        if (state.recording) { stopRecording(::leaveEditor); return }
        if (state.pendingAttachment != null || state.importFailed) { state = state.copy(error = "请先重试保存媒体，或放弃本次修改。"); return }
        media?.stopPlayback()
        runAction("暂时无法离开编辑，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                state = state.copy(editor = null, saveError = null, page = editor.returnPage)
                if (editor.returnPage == Page.Detail && editor.recordId != null) showDetail(editor.recordId, state.detailOrigin)
                if (editor.returnPage == Page.Records || editor.returnPage == Page.Search) refresh()
            }
        }
    }

    fun discard() {
        if (state.recording) cancelRecording()
        media?.stopPlayback()
        runAction("放弃修改失败，草稿仍保留，请重试。") {
            writes.withLock {
                val editor = state.editor ?: return@withLock
                (editor.draftId ?: pendingInitialId)?.let { withContext(Dispatchers.IO) { drafts.discardDraft(it) } }
                pendingInitialId = null
                failedPhoto = null
                state = state.copy(editor = null, page = editor.returnPage, saveError = null, pendingAttachment = null, importFailed = false)
                if (editor.returnPage == Page.Detail && editor.recordId != null) showDetail(editor.recordId, state.detailOrigin)
                if (editor.returnPage == Page.Records || editor.returnPage == Page.Search) refresh()
            }
        }
    }

    fun openRecord(id: RecordId) {
        runAction("记录读取失败，请重试。") { showDetail(id, state.page) }
    }

    private suspend fun showDetail(id: RecordId, origin: Page) {
        loadVersion++
        state = state.copy(page = Page.Detail, detailId = id, detail = null, detailOrigin = origin, loading = false)
        try {
            val (content, pending) = withContext(Dispatchers.IO) { published.openRecordContent(id) to drafts.listDrafts() }
            state = state.copy(detail = content, drafts = pending, error = if (content == null) "这条记录已不存在或已移入回收站。" else null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 完成/放弃可能已提交，详情读取失败不能被误报成写入失败。
            state = state.copy(error = "记录读取失败，请重试。")
        }
    }

    fun retryDetail() {
        val id = state.detailId ?: return
        runAction("记录读取失败，请重试。") { showDetail(id, state.detailOrigin) }
    }

    fun back() {
        if (state.busy) return
        when (state.page) {
            Page.Editor -> leaveEditor()
            Page.Detail -> home(state.detailOrigin)
            Page.Search -> home(Page.Records)
            Page.Records -> home(Page.Capture)
            Page.Capture -> Unit
        }
    }

    fun selectRecent(recent: Boolean) { state = state.copy(recent = recent) }

    fun query(value: String) {
        state = state.copy(query = value, results = emptyList())
        refresh()
    }

    fun refresh() {
        val version = ++loadVersion
        val page = state.page
        val query = state.query
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                if (page == Page.Search) {
                    val result = withContext(Dispatchers.IO) { browsing.queryRecords(query) }
                    if (version == loadVersion) state = state.copy(results = result, loading = false)
                } else {
                    val result = withContext(Dispatchers.IO) {
                        Triple(drafts.listDrafts(), browsing.listRecentRecords(), browsing.listEventTimeline())
                    }
                    if (version == loadVersion) state = state.copy(drafts = result.first, records = result.second,
                        timeline = result.third, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == loadVersion) state = state.copy(loading = false, error = "读取失败，请重试。")
            }
        }
    }

    private fun runAction(failure: String, afterSuccess: (() -> Unit)? = null, action: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            var success = false
            try { action(); success = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(error = failure) }
            finally { state = state.copy(busy = false) }
            if (success) afterSuccess?.invoke()
        }
    }

    companion object {
        fun canEdit(content: RecordContent?): Boolean =
            content != null
    }
}

private fun TextFieldValue.asEdit() = TextEdit(text, selection.end.coerceIn(0, text.length))

private fun ContentItem.withId(id: ItemId): ContentItem = when (this) {
    is ContentItem.Text -> copy(id = id)
    is ContentItem.Photo -> copy(id = id)
    is ContentItem.Audio -> copy(id = id)
    is ContentItem.Checklist -> copy(id = id)
}
