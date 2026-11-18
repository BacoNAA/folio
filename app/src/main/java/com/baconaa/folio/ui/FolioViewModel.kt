package com.baconaa.folio.ui

import android.net.Uri
import com.baconaa.folio.media.*
import java.util.UUID
import java.time.ZonedDateTime
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

enum class Page { Capture, Records, Search, Detail, Editor, Trash, TrashDetail, Places }

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
    val eventTime: EventTime? = null,
    val persistedEventTime: EventTime? = null,
    val tags: List<Tag> = emptyList(),
    val objects: List<RecordObject> = emptyList(),
    val place: Place? = null,
    val persistedPlaceId: PlaceId? = null,
    // 首次落盘分配后端 ID，但当前输入项及其键盘回调保留原身份；写入时再映射。
    val backendItemIds: Map<ItemId, ItemId> = emptyMap(),
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
    val sharedTags: List<Tag> = emptyList(),
    val tagsLoading: Boolean = false,
    val tagsError: String? = null,
    val filterTags: Set<TagId> = emptySet(),
    val sharedObjects: List<RecordObject> = emptyList(),
    val objectsLoading: Boolean = false,
    val objectsError: String? = null,
    val objectSaveVersion: Int = 0,
    val filterPeople: Set<ObjectId> = emptySet(),
    val filterThings: Set<ObjectId> = emptySet(),
    val starredOnly: Boolean = false,
    val trash: List<TrashPreview> = emptyList(),
    val trashId: RecordId? = null,
    val trashDetail: TrashedRecord? = null,
    val deleted: DeletedRecord? = null,
    val restoredId: RecordId? = null,
    val notice: String? = null,
    val cleanupPending: Boolean = false,
    val scrollRestoreVersion: Int = 0,
    val places: List<PlaceSummary> = emptyList(),
    val placeQuery: String = "",
    val placesLoading: Boolean = false,
    val placesError: String? = null,
    val filterPlace: Place? = null,
    val searchOrigin: Page = Page.Records,
    val placeRecent: Boolean = false,
    val linkingPlace: Place? = null,
)

data class DeletedRecord(val id: RecordId, val origin: Page, val query: String,
    val filterTags: Set<TagId>, val starredOnly: Boolean, val recent: Boolean,
    val filterPlace: Place?, val searchOrigin: Page, val placeRecent: Boolean,
    val filterPeople: Set<ObjectId>, val filterThings: Set<ObjectId>)

/** UI 状态由主线程持有；草稿写入串行，完成和离开等待最后一次输入落盘。 */
class FolioViewModel(
    private val drafts: TextDrafts,
    private val published: PublishedTextRecords,
    private val mixed: MixedContentDrafts,
    private val browsing: RecordBrowsing,
    private val media: EditorMedia? = null,
    private val times: RecordTimeEditing? = mixed as? RecordTimeEditing ?: published as? RecordTimeEditing,
    private val tags: RecordTags? = mixed as? RecordTags ?: published as? RecordTags,
    private val marking: RecordMarking? = published as? RecordMarking ?: mixed as? RecordMarking,
    private val trash: RecordTrash? = published as? RecordTrash ?: mixed as? RecordTrash,
    private val places: RecordPlaces? = published as? RecordPlaces ?: mixed as? RecordPlaces,
    private val objects: RecordObjects? = published as? RecordObjects ?: mixed as? RecordObjects,
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
        state = state.copy(page = page, error = null, loading = false, linkingPlace = if (page == Page.Search) state.linkingPlace else null)
        if (page == Page.Records || page == Page.Search || page == Page.Trash) refresh()
        if (page == Page.Search) { loadTags(); loadObjects() }
        if (page == Page.Places) loadPlaces(state.placeQuery)
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

    private fun begin(item: ContentItem? = null, currentTime: Boolean = true) {
        if (state.busy || state.editor != null) return
        loadVersion++
        state = state.copy(page = Page.Editor, editor = RecordEditor(
            items = listOfNotNull(item), activeItemId = item?.id,
            eventTime = if (currentTime) ZonedDateTime.now().let { EventTime(it.toLocalDate(), it.toLocalTime().withSecond(0).withNano(0), it.zone) } else null,
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
                                (snapshot.textValues[first.id] ?: TextFieldValue(first.text)).asEdit(), snapshot.eventTime))
                            is ContentItem.Checklist -> mixed.appendChecklist(null, first.entries, snapshot.eventTime)
                            else -> error("媒体必须先保存原件")
                        }
                    }
                    pendingInitialId = id
                    val content = withContext(Dispatchers.IO) { requireNotNull(mixed.openMixedDraft(id)) }
                    val savedTime = withContext(Dispatchers.IO) { times?.openDraftTimes(id)?.eventTime }
                    val current = state.editor ?: return true
                    val actualId = content.items.single().id
                    state = state.copy(editor = current.copy(draftId = id,
                        backendItemIds = mapOf(first.id to actualId),
                        persistedItems = content.items.map { it.withId(first.id) }, persistedEventTime = savedTime,
                        savedRevision = -1), saveError = null)
                    pendingInitialId = null
                } else {
                    withContext(Dispatchers.IO) {
                        val previous = snapshot.persistedItems.associateBy { it.id }
                        // 当前文字项最后保存，后端保留该项及项内光标。
                        val ordered = snapshot.items.sortedBy { it.id == snapshot.activeItemId }
                        for (item in ordered) {
                            val backendId = snapshot.backendItemIds[item.id] ?: item.id
                            if (item == previous[item.id] && !(item is ContentItem.Text && item.id == snapshot.activeItemId)) continue
                            when (item) {
                                is ContentItem.Text -> mixed.editTextItem(snapshot.draftId, backendId,
                                    (snapshot.textValues[item.id] ?: TextFieldValue(item.text)).asEdit())
                                is ContentItem.Photo -> mixed.editPhotoCaption(snapshot.draftId, backendId, item.caption)
                                is ContentItem.Audio -> mixed.editAudioCaption(snapshot.draftId, backendId, item.caption)
                                is ContentItem.Checklist -> mixed.editChecklist(snapshot.draftId, backendId, item.entries)
                            }
                        }
                        if (snapshot.eventTime != snapshot.persistedEventTime) {
                            requireNotNull(times).saveDraftEventTime(snapshot.draftId, snapshot.eventTime)
                        }
                        if (snapshot.place?.id != snapshot.persistedPlaceId) {
                            requireNotNull(places).selectDraftPlace(snapshot.draftId, snapshot.place?.id)
                        }
                    }
                    val current = state.editor ?: return true
                    state = state.copy(editor = current.copy(persistedItems = snapshot.items,
                        persistedEventTime = snapshot.eventTime, persistedPlaceId = snapshot.place?.id,
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
                val position = after?.let { editor.backendItemIds[it] ?: it }
                if (checklist) mixed.insertChecklist(id, position, listOf(ChecklistEntry(ChecklistEntryId.create(), "")))
                else mixed.insertText(id, position, TextEdit("", 0))
            }
        }
    }

    fun move(id: ItemId, direction: MoveDirection) = structure("移动失败，请重试。") {
        it.draftId?.let { draft -> withContext(Dispatchers.IO) { mixed.moveItem(draft, it.backendItemIds[id] ?: id, direction) } }
    }

    fun remove(id: ItemId) = structure("移除失败，请重试。") {
        media?.stopPlayback()
        if (it.draftId == null) state = state.copy(editor = it.copy(items = emptyList(), textValues = emptyMap(), activeItemId = null))
        else withContext(Dispatchers.IO) { mixed.removeItem(it.draftId, it.backendItemIds[id] ?: id) }
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

    fun prepareExternal(after: ItemId?, currentTime: Boolean = true, launch: (ExternalTarget) -> Unit) {
        if (state.recording || state.pendingAttachment != null || state.editor?.needsReload == true) return
        if (state.editor == null) begin(currentTime = currentTime)
        runAction("暂时无法打开媒体入口，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                launch(ExternalTarget(editor.draftId?.value, editor.recordId?.value, after?.let { (editor.backendItemIds[it] ?: it).value },
                    editor.returnPage, state.detailOrigin, editor.eventTime))
            }
        }
    }

    private suspend fun restoreTarget(target: ExternalTarget) {
        if (state.editor == null) {
            if (target.draftId != null) readEditor(DraftId(target.draftId), target.recordId?.let(::RecordId), target.returnPage)
            else state = state.copy(page = Page.Editor, editor = RecordEditor(returnPage = target.returnPage, eventTime = target.eventTime))
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
                is PendingAttachment.Photo -> if (editor.draftId == null) drafts.appendPhotos(null, listOf(pending.id), editor.eventTime)
                    else { mixed.insertPhoto(editor.draftId, pending.after, pending.id); editor.draftId }
                is PendingAttachment.Audio -> if (editor.draftId == null) mixed.appendAudio(null, pending.clip.id, initialEventTime = editor.eventTime)
                    else { mixed.insertAudio(editor.draftId, pending.after, pending.clip.id); editor.draftId }
            }
        }
        state = state.copy(editor = editor.copy(draftId = id), pendingAttachment = null)
        reloadCurrent()
    }

    fun startRecording(target: ExternalTarget) = runAction("无法开始录音，请检查麦克风权限或是否被占用。") {
        restoreTarget(target)
        recordingAfter = target.afterId?.let(::ItemId)?.let { state.editor?.backendItemIds?.get(it) ?: it }
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
        val eventTime = withContext(Dispatchers.IO) { times?.openDraftTimes(id)?.eventTime }
        val selectedTags = withContext(Dispatchers.IO) { tags?.openDraftTags(id).orEmpty() }
        val selectedObjects = withContext(Dispatchers.IO) { objects?.openDraftObjects(id).orEmpty() }
        val selectedPlace = withContext(Dispatchers.IO) { places?.openDraftPlace(id) }
        loadVersion++
        state = state.copy(page = Page.Editor, loading = false, error = null, saveError = null,
            detailOrigin = if (recordId != null && origin != Page.Detail) origin else state.detailOrigin,
            editor = RecordEditor(id, recordId, content.items,
                content.items.filterIsInstance<ContentItem.Text>().associate { item -> item.id to TextFieldValue(item.text,
                    TextRange(if (item.id == content.activeItemId) content.cursor else item.text.length)) },
                content.activeItemId, content.items, returnPage = origin, canUndo = canUndo,
                eventTime = eventTime, persistedEventTime = eventTime, tags = selectedTags, objects = selectedObjects,
                place = selectedPlace, persistedPlaceId = selectedPlace?.id))
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
        state.linkingPlace?.let { place ->
            runAction("关联地点失败，请重试；已有草稿仍保留。") {
                loadVersion++
                writes.withLock {
                    val draft = withContext(Dispatchers.IO) { drafts.editRecord(id).also { requireNotNull(places).selectDraftPlace(it, place.id) } }
                    state = state.copy(filterPlace = place, query = "", linkingPlace = null, detailOrigin = Page.Search,
                        page = Page.Editor, loading = false,
                        editor = RecordEditor(draftId = draft, recordId = id, returnPage = Page.Search, needsReload = true))
                    reloadCurrent()
                }
            }
            return
        }
        val origin = when (state.page) {
            Page.Detail -> state.detailOrigin
            Page.TrashDetail -> Page.Trash
            else -> state.page
        }
        runAction("记录读取失败，请重试。") { showDetail(id, origin) }
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
        state.linkingPlace?.let { state = state.copy(linkingPlace = null); openPlace(it); return }
        when (state.page) {
            Page.Editor -> leaveEditor()
            Page.Detail -> home(state.detailOrigin)
            Page.Search -> home(state.searchOrigin)
            Page.Places -> home(Page.Records)
            Page.Records -> home(Page.Capture)
            Page.Trash -> home(Page.Records)
            Page.TrashDetail -> home(Page.Trash)
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
        val filterTags = state.filterTags
        val starredOnly = state.starredOnly
        val place = state.filterPlace
        val people = state.filterPeople
        val things = state.filterThings
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                if (page == Page.Search) {
                    val (result, currentPlace) = withContext(Dispatchers.IO) {
                        browsing.queryRecords(query, filterTags, starredOnly, place?.id, people, things) to
                            place?.let { requireNotNull(requireNotNull(places).openPlace(it.id)) }
                    }
                    if (version == loadVersion) state = state.copy(results = result, filterPlace = currentPlace, loading = false)
                } else if (page == Page.Trash) {
                    val (result, pending) = withContext(Dispatchers.IO) {
                        val result = requireNotNull(trash).listTrash()
                        val pending = try { trash.retryPhotoCleanup() + trash.retryAudioCleanup() > 0 }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { true }
                        result to pending
                    }
                    if (version == loadVersion) state = state.copy(trash = result, cleanupPending = pending, loading = false)
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

    fun changeEventTime(value: EventTime?) {
        val editor = state.editor ?: return
        if (state.busy || editor.needsReload) return
        state = state.copy(editor = editor.copy(eventTime = value, revision = editor.revision + 1), saveError = null)
        save()
    }

    private var tagsVersion = 0
    fun loadTags() {
        val version = ++tagsVersion
        state = state.copy(tagsLoading = true, tagsError = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { requireNotNull(tags).listTags() }
                if (version == tagsVersion) state = state.copy(sharedTags = result, tagsLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == tagsVersion) state = state.copy(tagsLoading = false, tagsError = "标签读取失败，请重试。")
            }
        }
    }

    fun createTag(name: String) = editTags("新标签保存失败，请重试。") { id ->
        requireNotNull(tags).createDraftTag(id, name)
    }

    fun selectTag(tag: Tag, selected: Boolean) = editTags("标签选择保存失败，请重试。") { id ->
        val current = requireNotNull(requireNotNull(tags).openDraftTags(id)).map { it.id }
        tags.saveDraftTags(id, if (selected) (current + tag.id).distinct() else current - tag.id)
    }

    fun renameTag(tag: Tag, name: String, shared: Boolean) = editTags("标签改名失败，请重试。") { id ->
        if (shared) requireNotNull(tags).renameTag(tag.id, name)
        else requireNotNull(tags).renameDraftTag(id, tag.id, name)
    }

    private fun editTags(failure: String, action: suspend (DraftId) -> Unit) = structure(failure) { editor ->
        val id = editor.draftId ?: error("请先写下内容，再添加标签")
        withContext(Dispatchers.IO) { action(id) }
        loadTags()
    }

    fun filterByTags(ids: Set<TagId>) { state = state.copy(filterTags = ids); refresh() }
    fun filterStarred(value: Boolean) { state = state.copy(starredOnly = value); refresh() }
    fun clearFilters() { state = state.copy(filterTags = emptySet(), starredOnly = false, filterPlace = null,
        filterPeople = emptySet(), filterThings = emptySet()); refresh() }
    fun openSearch() { state = state.copy(searchOrigin = Page.Records, filterPlace = null, linkingPlace = null); home(Page.Search) }
    fun openStarred() { state = state.copy(filterTags = emptySet(), starredOnly = true, query = "", filterPlace = null, searchOrigin = Page.Records, linkingPlace = null,
        filterPeople = emptySet(), filterThings = emptySet()); home(Page.Search) }
    fun openTag(tag: Tag) { state = state.copy(filterTags = setOf(tag.id), starredOnly = false, query = "", filterPlace = null, searchOrigin = Page.Records, linkingPlace = null,
        filterPeople = emptySet(), filterThings = emptySet()); home(Page.Search) }

    private var objectsVersion = 0
    fun loadObjects() {
        val version = ++objectsVersion
        state = state.copy(objectsLoading = true, objectsError = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { requireNotNull(objects).listObjects() }
                if (version == objectsVersion) state = state.copy(sharedObjects = result, objectsLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (version == objectsVersion) state = state.copy(objectsLoading = false, objectsError = "人物与物件读取失败，请重试。") }
        }
    }
    fun createObject(kind: ObjectKind, info: ObjectInfo) = editObjects("新对象保存失败，请重试。") {
        requireNotNull(objects).createDraftObject(it, kind, info)
    }
    fun selectObject(value: RecordObject, selected: Boolean) = editObjects("对象选择保存失败，请重试。") { id ->
        val current = requireNotNull(requireNotNull(objects).openDraftObjects(id)).map { it.id }
        objects.saveDraftObjects(id, if (selected) (current + value.id).distinct() else current - value.id)
    }
    fun updateObject(value: RecordObject, info: ObjectInfo) {
        if (state.editor != null) editObjects("对象资料保存失败，请重试。") { id ->
            if (value.isDraft) requireNotNull(objects).updateDraftObject(id, value.id, info)
            else requireNotNull(objects).updateObject(value.id, info)
        } else runAction("对象资料保存失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(objects).updateObject(value.id, info) }
            state = state.copy(objectSaveVersion = state.objectSaveVersion + 1)
            loadObjects()
            refresh()
        }
    }
    private fun editObjects(failure: String, action: suspend (DraftId) -> Unit) = structure(failure) { editor ->
        withContext(Dispatchers.IO) { action(requireNotNull(editor.draftId)) }
        state = state.copy(objectSaveVersion = state.objectSaveVersion + 1)
        loadObjects()
    }
    fun filterObjects(kind: ObjectKind, ids: Set<ObjectId>) {
        state = if (kind == ObjectKind.PERSON) state.copy(filterPeople = ids) else state.copy(filterThings = ids)
        refresh()
    }
    fun openObject(value: RecordObject) {
        state = state.copy(query = "", filterTags = emptySet(), starredOnly = false, filterPlace = null,
            filterPeople = if (value.kind == ObjectKind.PERSON) setOf(value.id) else emptySet(),
            filterThings = if (value.kind == ObjectKind.THING) setOf(value.id) else emptySet(), searchOrigin = Page.Records, linkingPlace = null)
        home(Page.Search)
    }

    private var placesVersion = 0
    fun loadPlaces(query: String = "") {
        val version = ++placesVersion
        state = state.copy(placeQuery = query, placesLoading = true, placesError = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { requireNotNull(places).listPlaces(query) }
                if (version == placesVersion) state = state.copy(places = result, placesLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (version == placesVersion) state = state.copy(placesLoading = false, placesError = "地点读取失败，请重试。") }
        }
    }
    fun createPlace(info: PlaceInfo) = structure("新地点保存失败，请重试。") {
        withContext(Dispatchers.IO) { requireNotNull(places).createDraftPlace(requireNotNull(it.draftId), info) }
    }
    fun selectPlace(place: Place?) = structure("地点选择保存失败，请重试。") {
        withContext(Dispatchers.IO) { requireNotNull(places).selectDraftPlace(requireNotNull(it.draftId), place?.id) }
    }
    fun updatePrivatePlace(info: PlaceInfo) = structure("地点资料保存失败，请重试。") {
        withContext(Dispatchers.IO) { requireNotNull(places).updateDraftPlace(requireNotNull(it.draftId), requireNotNull(it.place).id, info) }
    }
    fun openPlace(place: Place) {
        state = state.copy(filterPlace = place, query = "", filterTags = emptySet(), starredOnly = false, searchOrigin = Page.Places, linkingPlace = null,
            filterPeople = emptySet(), filterThings = emptySet())
        home(Page.Search)
    }
    fun selectPlaceRecent(value: Boolean) { state = state.copy(placeRecent = value) }
    fun chooseExistingRecord() {
        val place = state.filterPlace ?: return
        state = state.copy(linkingPlace = place, filterPlace = null, query = "", filterTags = emptySet(), starredOnly = false,
            filterPeople = emptySet(), filterThings = emptySet())
        refresh()
    }
    fun clearPlaceFilter() { state = state.copy(filterPlace = null); refresh() }
    fun updatePlace(info: PlaceInfo) {
        val place = state.filterPlace ?: return
        runAction("地点资料保存失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(places).updatePlace(place.id, info) }
            state = state.copy(filterPlace = place.copy(info = info))
            refresh()
        }
    }
    fun newTextAtPlace() {
        if (state.busy || state.editor != null) return
        val place = state.filterPlace ?: return
        newText()
        state = state.copy(editor = state.editor!!.copy(place = place, returnPage = Page.Search))
    }

    fun setStarred(value: Boolean) = mark { id -> requireNotNull(marking).setStarred(id, value) }
    fun setPinned(value: Boolean) = mark { id -> requireNotNull(marking).setPinned(id, value) }
    private fun mark(action: suspend (RecordId) -> Unit) {
        if (state.page != Page.Detail) return
        val id = state.detailId ?: return
        runAction("标记保存失败，请重试。") {
            withContext(Dispatchers.IO) { action(id) }
            showDetail(id, state.detailOrigin)
        }
    }

    fun deleteRecord() {
        if (state.page != Page.Detail) return
        val id = state.detailId ?: return
        val origin = state.detailOrigin
        val context = DeletedRecord(id, origin, state.query, state.filterTags, state.starredOnly, state.recent, state.filterPlace, state.searchOrigin, state.placeRecent,
            state.filterPeople, state.filterThings)
        val hasDraft = state.drafts.any { it.recordId == id }
        media?.stopPlayback()
        runAction("移入回收站失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(trash).moveToTrash(id) }
            state = state.copy(page = origin, detail = null, detailId = null, savedId = null,
                deleted = context, restoredId = null,
                notice = if (hasDraft) "已移入回收站，未完成修改也已保留" else "已移入回收站")
            refresh()
        }
    }

    fun undoDelete() {
        if (state.editor != null) return
        val deleted = state.deleted ?: return
        runAction("恢复失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(trash).restoreRecord(deleted.id) }
            state = state.copy(page = deleted.origin, deleted = null, notice = "已撤销删除",
                query = deleted.query, filterTags = deleted.filterTags, starredOnly = deleted.starredOnly, recent = deleted.recent,
                filterPlace = deleted.filterPlace, searchOrigin = deleted.searchOrigin, placeRecent = deleted.placeRecent,
                filterPeople = deleted.filterPeople, filterThings = deleted.filterThings,
                scrollRestoreVersion = state.scrollRestoreVersion + 1)
            refresh()
        }
    }

    fun openTrashRecord(id: RecordId) = runAction("回收站记录读取失败，请重试。") {
        media?.stopPlayback()
        loadVersion++
        state = state.copy(page = Page.TrashDetail, trashId = id, trashDetail = null, loading = false)
        val content = withContext(Dispatchers.IO) { requireNotNull(trash).openTrashRecord(id) }
        state = state.copy(trashDetail = content, error = if (content == null) "这条记录已不在回收站，请返回刷新。" else null)
    }

    fun retryTrashDetail() { state.trashId?.let(::openTrashRecord) }
    fun restoreRecord(id: RecordId) = runAction("恢复失败，请重试。") {
        media?.stopPlayback()
        withContext(Dispatchers.IO) { requireNotNull(trash).restoreRecord(id) }
        state = state.copy(page = Page.Trash, trashDetail = null, restoredId = id, deleted = null, notice = "已恢复")
        refresh()
    }

    fun deletePermanently(id: RecordId) = runAction("彻底删除失败，请重试。") {
        media?.stopPlayback()
        val result = withContext(Dispatchers.IO) { requireNotNull(trash).deletePermanently(id) }
        state = state.copy(page = Page.Trash, trashDetail = null, restoredId = null, deleted = null,
            notice = "已彻底删除", cleanupPending = result.pendingPhotoCount != 0 || result.pendingAudioCount != 0)
        refresh()
    }

    fun retryCleanup() = runAction("部分文件尚未清理，请稍后重试。") {
        val remaining = withContext(Dispatchers.IO) { requireNotNull(trash).retryPhotoCleanup() + trash.retryAudioCleanup() }
        state = state.copy(cleanupPending = remaining > 0)
    }

    fun dismissNotice() { state = state.copy(notice = null, deleted = null, restoredId = null) }

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
