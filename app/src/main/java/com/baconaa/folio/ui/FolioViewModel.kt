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

enum class Page { Capture, Records, Search, Detail, Editor, Trash, TrashDetail, Places, Groups, GroupDetail, GroupPicker, GroupDestination, GroupGuide }

internal val Page.isGroupReadingPage get() = this == Page.GroupDetail || this == Page.GroupGuide
internal val Page.isGroupPage get() = isGroupReadingPage || this == Page.Groups || this == Page.GroupPicker || this == Page.GroupDestination

data class GuideEditing(val layout: GuideLayout, val conflicting: GroupGuideContent? = null)

data class GroupSelection(
    val origin: Page, val creationId: RecordGroupId,
    val members: List<RecordId> = emptyList(), val name: String = "",
)

data class FileImportFailure(val type: ImportType, val after: ItemId?, val message: String)

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
    val title: String = "",
    val persistedTitle: String = "",
    // 先写标题时，页面的空输入框尚不是草稿内容项。
    val unboundItem: ItemId? = null,
    val photoTimeOffer: PhotoTimeOffer? = null,
    val markingPhoto: ContentItem.Photo? = null,
    // 首次落盘分配后端 ID，但当前输入项及其键盘回调保留原身份；写入时再映射。
    val backendItemIds: Map<ItemId, ItemId> = emptyMap(),
    val fileImportFailure: FileImportFailure? = null,
    val invalidLink: InvalidLinkAddress? = null,
) {
    fun position(id: ItemId?) = id?.takeUnless { it == unboundItem }?.let { backendItemIds[it] ?: it }
    val value: TextFieldValue get() = textValues.values.firstOrNull() ?: TextFieldValue()
    val hasContent: Boolean get() = items.any { it.hasContent() }
}

private fun ContentItem.hasContent() = when (this) {
    is ContentItem.Text -> text.isNotBlank()
    is ContentItem.Checklist -> entries.any { it.text.isNotBlank() }
    is ContentItem.Link -> hasInput
    else -> true
}

/** 同一地点既可作为普通搜索条件，也可作为有资料和记录操作的地点小册。 */
data class PlaceScope(val place: Place, val isBook: Boolean)

data class FolioState(
    val page: Page = Page.Capture,
    val editor: RecordEditor? = null,
    val drafts: List<DraftPreview> = emptyList(),
    val draftReadRetry: DraftPreview? = null,
    val records: List<RecordCard> = emptyList(),
    val groups: List<RecordGroup> = emptyList(),
    val groupId: RecordGroupId? = null,
    val groupContent: RecordGroupContent? = null,
    val groupGuide: GroupGuideContent? = null,
    val guideEdit: GuideEditing? = null,
    val groupSelection: GroupSelection? = null,
    val groupFeedback: String? = null,
    val groupReadError: String? = null,
    val timeline: EventTimeline = EventTimeline(emptyList(), emptyList()),
    val recent: Boolean = false,
    val query: String = "",
    val results: List<RecordCard> = emptyList(),
    val possibleResults: List<RecordCard> = emptyList(),
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
    val mediaTypes: Set<MediaFilter> = emptySet(),
    val eventDateFilter: EventDateFilter? = null,
    val undatedMatchCount: Int = 0,
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
    val placeScope: PlaceScope? = null,
    val searchOrigin: Page = Page.Records,
    val placeRecent: Boolean = false,
    val linkingPlace: Place? = null,
) {
    val filterPlace: Place? get() = placeScope?.place
    val placeBook: Place? get() = placeScope?.takeIf { it.isBook }?.place
}

data class DeletedRecord(val id: RecordId, val origin: Page, val query: String,
    val filterTags: Set<TagId>, val starredOnly: Boolean, val recent: Boolean,
    val placeScope: PlaceScope?, val searchOrigin: Page, val placeRecent: Boolean,
    val filterPeople: Set<ObjectId>, val filterThings: Set<ObjectId>, val mediaTypes: Set<MediaFilter>,
    val eventDateFilter: EventDateFilter?, val groupId: RecordGroupId? = null)

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
    private val imports: MediaImports? = mixed as? MediaImports ?: published as? MediaImports,
    private val recordGroups: RecordGroups? = published as? RecordGroups ?: mixed as? RecordGroups,
    private val groupGuides: RecordGroupGuides? = published as? RecordGroupGuides ?: mixed as? RecordGroupGuides,
) : ViewModel() {
    var state by mutableStateOf(FolioState())
        private set
    private val writes = Mutex()
    private var loadVersion = 0
    private var pendingInitialId: DraftId? = null

    fun home(page: Page) {
        if (state.busy || state.editor != null) return
        navigate(page)
    }

    private fun navigate(page: Page) {
        media?.stopPlayback()
        loadVersion++
        state = state.copy(page = page, error = null, loading = false, draftReadRetry = null,
            groupReadError = null, groupFeedback = null,
            linkingPlace = if (page == Page.Search) state.linkingPlace else null)
        if (page == Page.Records || page == Page.Search || page == Page.Trash || page.isGroupPage) refresh()
        if (page == Page.Search) { loadTags(); loadObjects() }
        if (page == Page.Places) loadPlaces(directoryPlaceQuery)
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

    fun newText() = begin(emptyItem(EmptyInput.Text))
    fun newChecklist() = begin(emptyItem(EmptyInput.Checklist))

    private fun emptyItem(input: EmptyInput) = when (input) {
        EmptyInput.Text -> ContentItem.Text(newItemId(), "")
        EmptyInput.Checklist -> ContentItem.Checklist(newItemId(), listOf(ChecklistEntry(ChecklistEntryId.create(), "")))
        EmptyInput.Link -> ContentItem.Link(newItemId())
    }

    private fun begin(item: ContentItem? = null, currentTime: Boolean = true) {
        if (state.busy || state.editor != null) return
        loadVersion++
        state = state.copy(page = Page.Editor, editor = RecordEditor(
            items = listOfNotNull(item), activeItemId = item?.id,
            eventTime = if (currentTime) ZonedDateTime.now().let { EventTime(it.toLocalDate(), it.toLocalTime().withSecond(0).withNano(0), it.zone) } else null,
            textValues = if (item is ContentItem.Text) mapOf(item.id to TextFieldValue()) else emptyMap()),
            savedId = null, error = null, saveError = null, loading = false)
    }

    fun changeTitle(title: String) {
        val editor = state.editor ?: return
        if (state.busy || editor.needsReload || editor.title == title) return
        state = state.copy(editor = editor.copy(title = title, revision = editor.revision + 1), saveError = null)
        save()
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

    fun openPhotoMarkup(photo: ContentItem.Photo) {
        val editor = state.editor ?: return
        if (!state.busy) state = state.copy(editor = editor.copy(markingPhoto = photo), error = null)
    }

    fun changePhotoMarkup(change: (List<PhotoStroke>) -> List<PhotoStroke>) {
        val editor = state.editor ?: return
        val photo = editor.markingPhoto ?: return
        if (!state.busy) state = state.copy(editor = editor.copy(markingPhoto = photo.copy(strokes = change(photo.strokes))), error = null)
    }

    fun cancelPhotoMarkup() {
        val editor = state.editor ?: return
        if (!state.busy) state = state.copy(editor = editor.copy(markingPhoto = null), error = null)
    }

    fun savePhotoMarkup() = runAction("标注保存失败，请重试。") {
        writes.withLock {
            if (!saveLatest()) return@withLock
            val editor = state.editor ?: return@withLock
            val photo = editor.markingPhoto ?: return@withLock
            val saved = try {
                withContext(Dispatchers.IO) {
                    mixed.editPhotoStrokes(requireNotNull(editor.draftId), requireNotNull(editor.position(photo.id)), photo.strokes)
                }
            } catch (invalid: IllegalArgumentException) {
                state = state.copy(error = invalid.message ?: "标注保存失败，请重试。")
                return@withLock
            }
            val item = saved.copy(id = photo.id)
            state = state.copy(editor = editor.copy(markingPhoto = null,
                items = editor.items.map { if (it.id == item.id) item else it },
                persistedItems = editor.persistedItems.map { if (it.id == item.id) item else it }))
        }
    }

    fun changeLink(link: ContentItem.Link) {
        updateItem(link.id) { link }
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
            try {
                if (snapshot.draftId == null && !snapshot.hasContent && pendingInitialId == null) {
                    if (snapshot.title.isBlank()) return true
                    val id = withContext(Dispatchers.IO) {
                        requireNotNull(drafts.saveDraftTitle(null, snapshot.title, snapshot.eventTime))
                    }
                    val current = state.editor ?: return true
                    state = state.copy(editor = current.copy(draftId = id, persistedTitle = snapshot.title,
                        persistedEventTime = snapshot.eventTime, unboundItem = snapshot.items.singleOrNull()?.id))
                    continue
                }
                if (snapshot.draftId == null) {
                    // 未落盘时仅有一个输入项；结构操作先等待保存，再交给混排接口。
                    val first = snapshot.items.single()
                    val id = pendingInitialId ?: withContext(Dispatchers.IO) {
                        when (first) {
                            is ContentItem.Text -> requireNotNull(drafts.saveDraft(null,
                                (snapshot.textValues[first.id] ?: TextFieldValue(first.text)).asEdit(), snapshot.eventTime))
                            is ContentItem.Checklist -> mixed.appendChecklist(null, first.entries, snapshot.eventTime)
                            is ContentItem.Link -> mixed.insertLink(null, null, first, snapshot.eventTime)
                            else -> error("媒体必须先保存原件")
                        }
                    }
                    pendingInitialId = id
                    val content = withContext(Dispatchers.IO) { requireNotNull(mixed.openMixedDraft(id)) }
                    val savedTime = withContext(Dispatchers.IO) { times?.openDraftTimes(id)?.eventTime }
                    val current = state.editor ?: return true
                    val actualId = content.items.single().id
                    state = state.copy(editor = current.copy(draftId = id,
                        unboundItem = null, backendItemIds = mapOf(first.id to actualId),
                        persistedItems = content.items.map { it.withId(first.id) }, persistedEventTime = savedTime,
                        savedRevision = -1), saveError = null)
                    pendingInitialId = null
                } else {
                    val first = snapshot.items.firstOrNull { it.id == snapshot.unboundItem && it.hasContent() }
                    if (first != null) {
                        val itemId = withContext(Dispatchers.IO) {
                            when (first) {
                                is ContentItem.Text -> mixed.insertText(snapshot.draftId, null,
                                    (snapshot.textValues[first.id] ?: TextFieldValue(first.text)).asEdit())
                                is ContentItem.Checklist -> mixed.insertChecklist(snapshot.draftId, null, first.entries)
                                is ContentItem.Link -> {
                                    mixed.insertLink(snapshot.draftId, null, first)
                                    first.id
                                }
                                else -> error("媒体必须先保存原件")
                            }
                        }
                        val current = state.editor ?: return true
                        state = state.copy(editor = current.copy(unboundItem = null,
                            backendItemIds = current.backendItemIds + (first.id to itemId), persistedItems = listOf(first)))
                        continue
                    }
                    val savedId = withContext(Dispatchers.IO) {
                        val previous = snapshot.persistedItems.associateBy { it.id }
                        // 当前文字项最后保存，后端保留该项及项内光标。
                        val ordered = snapshot.items.sortedBy { it.id == snapshot.activeItemId }
                        for (item in ordered) {
                            if (item.id == snapshot.unboundItem) continue
                            val backendId = snapshot.backendItemIds[item.id] ?: item.id
                            if (item == previous[item.id] && !(item is ContentItem.Text && item.id == snapshot.activeItemId)) continue
                            when (item) {
                                is ContentItem.Text -> mixed.editTextItem(snapshot.draftId, backendId,
                                    (snapshot.textValues[item.id] ?: TextFieldValue(item.text)).asEdit())
                                is ContentItem.Photo -> mixed.editPhotoCaption(snapshot.draftId, backendId, item.caption)
                                is ContentItem.Link -> mixed.editLink(snapshot.draftId, item.copy(id = backendId))
                                is ContentItem.Pdf, is ContentItem.Video -> Unit // 文件信息随原件导入。
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
                        if (snapshot.title != snapshot.persistedTitle) drafts.saveDraftTitle(snapshot.draftId, snapshot.title)
                        else snapshot.draftId
                    }
                    val current = state.editor ?: return true
                    state = state.copy(editor = current.copy(draftId = savedId, persistedTitle = snapshot.title,
                        persistedItems = snapshot.items.filterNot { it.id == snapshot.unboundItem },
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

    fun addText(after: ItemId? = null) = addEmpty(after, EmptyInput.Text)
    fun addChecklist(after: ItemId? = null) = addEmpty(after, EmptyInput.Checklist)
    fun addLink(after: ItemId? = null) = addEmpty(after, EmptyInput.Link)

    private fun addEmpty(after: ItemId?, input: EmptyInput) {
        if (state.recording || state.pendingAttachment != null) return
        structure("添加失败，请重试。") { editor ->
            val id = editor.draftId
            if (id == null) {
                val item = emptyItem(input)
                state = state.copy(editor = editor.copy(items = listOf(item), activeItemId = item.id,
                    textValues = if (item is ContentItem.Text) mapOf(item.id to TextFieldValue()) else emptyMap(), revision = 0, savedRevision = 0))
            } else withContext(Dispatchers.IO) {
                val position = editor.position(after)
                when (input) {
                    EmptyInput.Text -> mixed.insertText(id, position, TextEdit("", 0))
                    EmptyInput.Checklist -> mixed.insertChecklist(id, position, listOf(ChecklistEntry(ChecklistEntryId.create(), "")))
                    EmptyInput.Link -> mixed.insertLink(id, position, ContentItem.Link(newItemId()))
                }
            }
        }
    }

    fun move(id: ItemId, direction: MoveDirection) = structure("移动失败，请重试。") {
        it.draftId?.let { draft -> withContext(Dispatchers.IO) { mixed.moveItem(draft, it.backendItemIds[id] ?: id, direction) } }
    }

    fun remove(id: ItemId) = structure("移除失败，请重试。") {
        media?.stopPlayback()
        if (it.draftId == null || id == it.unboundItem) state = state.copy(editor = it.copy(items = emptyList(), textValues = emptyMap(), activeItemId = null, unboundItem = null))
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

    fun prepareExternal(after: ItemId?, currentTime: Boolean = true, screenshotFor: ItemId? = null, launch: (ExternalTarget) -> Unit) {
        if (state.recording || state.pendingAttachment != null || state.editor?.needsReload == true) return
        if (state.editor == null) begin(currentTime = currentTime)
        runAction("暂时无法打开媒体入口，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                val emptyInput = if (editor.draftId == null || (screenshotFor != null && screenshotFor == editor.unboundItem)) when (editor.items.singleOrNull()) {
                    is ContentItem.Text -> EmptyInput.Text
                    is ContentItem.Checklist -> EmptyInput.Checklist
                    is ContentItem.Link -> EmptyInput.Link
                    else -> null
                } else null
                launch(ExternalTarget(editor.draftId?.value, editor.recordId?.value,
                    editor.draftId?.let { editor.position(after)?.value }, editor.returnPage, state.detailOrigin,
                    editor.eventTime, emptyInput = emptyInput, placeBookId = state.placeBook?.id?.value,
                    groupId = state.groupId?.value.takeIf { state.detailOrigin.isGroupReadingPage },
                    linkItemId = editor.draftId?.let { editor.position(screenshotFor)?.value }))
            }
        }
    }

    private suspend fun restoreTarget(target: ExternalTarget) {
        if (state.editor == null) {
            val book = target.placeBookId?.let { withContext(Dispatchers.IO) { places?.openPlace(PlaceId(it)) } }
            if (target.draftId != null) {
                readEditor(DraftId(target.draftId), target.recordId?.let(::RecordId), target.returnPage)
                val editor = state.editor
                if (target.emptyInput == EmptyInput.Link && editor?.unboundItem != null) {
                    val link = ContentItem.Link(editor.unboundItem)
                    state = state.copy(editor = editor.copy(items = listOf(link), activeItemId = link.id, textValues = emptyMap()))
                }
            }
            else {
                val item = target.emptyInput?.let(::emptyItem)
                state = state.copy(page = Page.Editor, editor = RecordEditor(items = listOfNotNull(item),
                    activeItemId = item?.id, returnPage = target.returnPage, eventTime = target.eventTime,
                    place = book.takeIf { target.returnPage == Page.Search }))
            }
            state = state.copy(detailOrigin = target.detailOrigin, groupId = target.groupId?.let(::RecordGroupId) ?: state.groupId,
                placeScope = book?.let { PlaceScope(it, isBook = true) } ?: state.placeScope)
        }
        check(state.editor != null && state.editor?.draftId?.value == target.draftId) { "原草稿已不可编辑" }
    }

    fun receiveDocument(uri: Uri?, type: ImportType, target: ExternalTarget) {
        if (uri == null && state.editor != null) return
        runAction("无法回到原草稿，请从记录页重新打开。") {
            writes.withLock {
                restoreTarget(target)
                if (uri == null || !saveLatest()) return@withLock
                val editor = requireNotNull(state.editor)
                val after = target.afterId?.let(::ItemId)
                val result = try {
                    requireNotNull(imports).importMedia(uri, type, editor.draftId, after, target.eventTime, editor.place?.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val message = (failure as? MediaImportException)?.message ?: "文件未能加入草稿，请重新选择或继续编辑。"
                    state = state.copy(editor = editor.copy(fileImportFailure = FileImportFailure(type, after, message)))
                    return@withLock
                }
                state = state.copy(editor = editor.copy(draftId = result.draftId, fileImportFailure = null))
                reloadCurrent()
            }
        }
    }

    fun dismissFileImportFailure() {
        state.editor?.let { state = state.copy(editor = it.copy(fileImportFailure = null)) }
    }

    fun receiveLinkScreenshot(uri: Uri?, target: ExternalTarget) {
        if (uri == null && state.editor != null) return
        runAction("截图保存失败，请重新选择。原草稿仍保留。") {
            writes.withLock {
                restoreTarget(target)
                if (uri == null || !saveLatest()) return@withLock
                val editor = requireNotNull(state.editor)
                val id = requireNotNull(imports).importLinkScreenshot(uri, editor.draftId, target.linkItemId?.let(::ItemId),
                    target.eventTime, editor.place?.id)
                state = state.copy(editor = editor.copy(draftId = id))
                reloadCurrent()
            }
        }
    }

    suspend fun pdfViewIntent(item: ContentItem.Pdf) = withContext(Dispatchers.IO) {
        requireNotNull(imports).pdfViewIntent(item)
    }

    suspend fun videoSource(item: ContentItem.Video) = withContext(Dispatchers.IO) { requireNotNull(imports).videoSource(item) }

    suspend fun videoThumbnail(item: ContentItem.Video) = withContext(Dispatchers.IO) { imports?.videoThumbnail(item) }

    fun receivePhoto(uri: Uri, target: ExternalTarget) {
        failedPhoto = uri to target
        runAction("照片保存失败，原有内容仍保留，请使用下方入口重试。") {
            try {
                restoreTarget(target)
                val id = requireNotNull(media).importPhoto(uri)
                state = state.copy(pendingAttachment = PendingAttachment.Photo(id, target.afterId?.let(::ItemId), target.suggestPhotoTime), importFailed = false)
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
        if (pending is PendingAttachment.Photo && pending.suggestTime) {
            try {
                val capture = media?.readCaptureTime(pending.id) ?: return
                val current = state.editor ?: return
                if (current.draftId == id && !current.needsReload) {
                    val stamp = capture.localDateTime
                    val time = EventTime.Exact(stamp.toLocalDate(), stamp.toLocalTime(), capture.offset ?: java.time.ZoneId.systemDefault())
                    state = state.copy(editor = current.copy(photoTimeOffer = PhotoTimeOffer(time, capture.offset == null)))
                }
            } catch (_: java.io.IOException) { state = state.copy(error = "照片已加入草稿，无法读取拍摄时间。可手动填写事件时间。") }
        }
    }

    fun resolvePhotoTime(adopt: Boolean) {
        val editor = state.editor ?: return
        val offer = editor.photoTimeOffer ?: return
        if (state.busy || editor.needsReload) return
        state = state.copy(editor = editor.copy(photoTimeOffer = null))
        if (adopt) changeEventTime(offer.time)
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

    fun resume(preview: DraftPreview) {
        val origin = state.page
        runAction("草稿读取失败，请重试。") {
            state = state.copy(draftReadRetry = preview)
            readEditor(preview.id, preview.recordId, origin)
            state = state.copy(draftReadRetry = null)
        }
    }

    fun retryDraftRead() { state.draftReadRetry?.let(::resume) }

    fun editRecord() {
        val id = state.detailId ?: return
        if (!canEdit(state.detail)) return
        runAction("暂时无法打开编辑，请重试。") {
            val draftId = withContext(Dispatchers.IO) { drafts.editRecord(id) }
            readEditor(draftId, id, Page.Detail)
        }
    }

    private suspend fun readEditor(id: DraftId, recordId: RecordId?, origin: Page) {
        val content = withContext(Dispatchers.IO) { mixed.openMixedDraft(id) }
        if (content == null) {
            state = state.copy(error = "这份草稿已不存在，请返回记录页刷新。")
            return
        }
        val titleOnlyInput = if (content.title != null && content.items.isEmpty())
            ContentItem.Text(ItemId(UUID.randomUUID().toString()), "") else null
        val editorItems = titleOnlyInput?.let { listOf(it) } ?: content.items
        val canUndo = withContext(Dispatchers.IO) { mixed.canUndoRemoval(id) }
        val eventTime = withContext(Dispatchers.IO) { times?.openDraftTimes(id)?.eventTime }
        val selectedTags = withContext(Dispatchers.IO) { tags?.openDraftTags(id).orEmpty() }
        val selectedObjects = withContext(Dispatchers.IO) { objects?.openDraftObjects(id).orEmpty() }
        val selectedPlace = withContext(Dispatchers.IO) { places?.openDraftPlace(id) }
        loadVersion++
        state = state.copy(page = Page.Editor, loading = false, error = null, saveError = null,
            detailOrigin = if (recordId != null && origin != Page.Detail) origin else state.detailOrigin,
            editor = RecordEditor(id, recordId, editorItems,
                editorItems.filterIsInstance<ContentItem.Text>().associate { item -> item.id to TextFieldValue(item.text,
                    TextRange(if (item.id == content.activeItemId) content.cursor else item.text.length)) },
                content.activeItemId, content.items, unboundItem = titleOnlyInput?.id, title = content.title.orEmpty(), persistedTitle = content.title.orEmpty(), returnPage = origin, canUndo = canUndo,
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
                val recordId = try {
                    withContext(Dispatchers.IO) { mixed.completeMixedDraft(id) }
                } catch (invalid: InvalidLinkAddress) {
                    state = state.copy(editor = editor.copy(activeItemId = invalid.itemId, invalidLink = invalid))
                    return@withLock
                }
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
                    state = state.copy(placeScope = PlaceScope(place, isBook = true), query = "", linkingPlace = null, detailOrigin = Page.Search,
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
            Page.Groups -> home(Page.Records)
            Page.GroupDetail -> home(Page.Groups)
            Page.GroupGuide -> if (state.guideEdit == null) home(Page.GroupDetail) else {
                state = state.copy(guideEdit = null, groupFeedback = null)
                refresh()
            }
            Page.GroupPicker -> {
                val origin = state.groupSelection?.origin ?: Page.Groups
                state = state.copy(groupSelection = null)
                home(origin)
            }
            Page.GroupDestination -> home(Page.GroupPicker)
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
        state = state.copy(query = value, results = emptyList(), possibleResults = emptyList())
        refresh()
    }

    fun refresh() {
        val version = ++loadVersion
        val page = state.page
        val query = state.query
        val filterTags = state.filterTags
        val starredOnly = state.starredOnly
        val placeScope = state.placeScope
        val people = state.filterPeople
        val things = state.filterThings
        val mediaTypes = state.mediaTypes
        val eventDateFilter = state.eventDateFilter
        state = state.copy(loading = true, error = null, draftReadRetry = null, groupReadError = null)
        viewModelScope.launch {
            try {
                if (page.isGroupPage) {
                    readGroupPage(page, version)
                } else if (page == Page.Search) {
                    val (result, currentScope) = withContext(Dispatchers.IO) {
                        browsing.queryRecords(RecordQuery(query, filterTags, starredOnly, placeScope?.place?.id, people, things, mediaTypes,
                            eventDateFilter.forQuery())) to
                            placeScope?.let { scope -> scope.copy(place = requireNotNull(places?.openPlace(scope.place.id))) }
                    }
                    if (version == loadVersion) state = state.copy(results = result.certain, possibleResults = result.possible, undatedMatchCount = result.undatedCount,
                        placeScope = currentScope, loading = false)
                } else if (page == Page.Trash) {
                    val (result, pending) = withContext(Dispatchers.IO) {
                        val result = requireNotNull(trash).listTrash()
                        val pending = try { trash.retryMediaCleanup() > 0 }
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
                if (version == loadVersion) state = if (page.isGroupPage) state.copy(loading = false, groupReadError = "读取失败，请重试。")
                    else state.copy(loading = false, error = "读取失败，请重试。")
            }
        }
    }

    private suspend fun readGroupPage(page: Page, version: Int) {
        when (page) {
            Page.GroupGuide -> {
                val id = requireNotNull(state.groupId)
                val content = withContext(Dispatchers.IO) { requireNotNull(groupGuides).openGuide(id) }
                if (version == loadVersion) state = state.copy(groupGuide = content, loading = false)
            }
            Page.GroupDetail -> {
                val id = requireNotNull(state.groupId)
                val content = withContext(Dispatchers.IO) { requireNotNull(recordGroups).openGroup(id) }
                if (version == loadVersion) state = state.copy(groupContent = content, loading = false)
            }
            Page.GroupPicker -> {
                val records = withContext(Dispatchers.IO) { browsing.listRecentRecords() }
                if (version != loadVersion) return
                val selection = requireNotNull(state.groupSelection)
                val active = records.mapTo(hashSetOf()) { it.id }
                val valid = selection.members.filter { it in active }
                state = state.copy(records = records, groupSelection = selection.copy(members = valid), loading = false,
                    groupFeedback = if (valid.size == selection.members.size) state.groupFeedback else "部分记录已失效，请确认剩余选择。")
            }
            Page.Groups, Page.GroupDestination -> {
                val groups = withContext(Dispatchers.IO) { requireNotNull(recordGroups).listGroups() }
                if (version == loadVersion) state = state.copy(groups = groups, loading = false)
            }
            else -> error("无效的记录组页面")
        }
    }

    fun openGroup(id: RecordGroupId) {
        if (state.busy) return
        state = state.copy(groupId = id, groupContent = null, groupGuide = null, guideEdit = null)
        navigate(Page.GroupDetail)
    }

    fun openGroupGuide() {
        if (state.busy || state.groupId == null) return
        state = state.copy(groupGuide = null, guideEdit = null)
        navigate(Page.GroupGuide)
    }

    fun beginGuideEdit() {
        val guide = state.groupGuide ?: return
        if (!state.busy) state = state.copy(guideEdit = GuideEditing(guide.layout), groupFeedback = null)
    }

    fun changeGuide(change: (GuideLayout) -> GuideLayout) {
        val editor = state.guideEdit ?: return
        if (!state.busy) state = state.copy(guideEdit = editor.copy(layout = change(editor.layout)), error = null)
    }

    fun saveGuide() {
        val guide = state.groupGuide ?: return
        val editor = state.guideEdit ?: return
        if (editor.conflicting != null) return
        runAction("导览保存失败，当前输入已保留。") {
            when (val result = withContext(Dispatchers.IO) {
                requireNotNull(groupGuides).saveGuide(guide.content.group.id, guide.layout, editor.layout)
            }) {
                GuideUpdate.Saved -> {
                    state = state.copy(guideEdit = null, groupFeedback = "导览已保存。")
                    refresh()
                }
                GuideUpdate.MissingGroup -> state = state.copy(groupFeedback = "原记录组已删除，当前输入仍可查看。")
                is GuideUpdate.Changed -> showGuideConflict(guide, editor, result.current)
            }
        }
    }

    private fun showGuideConflict(original: GroupGuideContent, editor: GuideEditing, latest: GroupGuideContent) {
        val base = original.layout
        val current = latest.layout
        val byId = current.members.associateBy { it.recordId }
        val removed = original.content.members.filter { it.id !in byId }
        val removedText = when (removed.size) {
            0 -> ""
            1 -> "「${removed.first().guideLabel()}」已移出。"
            else -> "「${removed.first().guideLabel()}」等 ${removed.size} 条记录已移出。"
        }
        val changedAssignment = base.members.any { before -> byId[before.recordId]?.let { it.sectionId != before.sectionId } == true }
        if (base.description != current.description || base.sections != current.sections || changedAssignment) {
            state = state.copy(guideEdit = editor.copy(conflicting = latest),
                groupFeedback = removedText + "导览已在别处修改，当前输入已保留。")
            return
        }
        // 只有收录或成员顺序变化时，保留尚在组内的分段选择、段序与输入的说明。
        val edited = editor.layout.members.associateBy { it.recordId }
        val sections = editor.layout.sections.mapTo(hashSetOf()) { it.id }
        val members = current.members.map { member -> edited[member.recordId] ?: member.copy(sectionId = member.sectionId?.takeIf { it in sections }) }
        state = state.copy(groupGuide = latest, guideEdit = GuideEditing(editor.layout.copy(members = members)),
            groupFeedback = removedText + "已按最新成员顺序更新，请核对后保存。")
    }

    fun useLatestGuide() {
        val latest = state.guideEdit?.conflicting ?: return
        state = state.copy(groupGuide = latest, guideEdit = GuideEditing(latest.layout), groupFeedback = null)
    }

    fun pickGroupMembers() {
        if (state.busy) return
        val selection = GroupSelection(state.page, RecordGroupId(UUID.randomUUID().toString()))
        state = state.copy(groupSelection = selection)
        navigate(Page.GroupPicker)
    }

    fun toggleGroupMember(id: RecordId) {
        val selection = state.groupSelection ?: return
        if (state.busy || state.loading) return
        val members = if (id in selection.members) selection.members - id else selection.members + id
        state = state.copy(groupSelection = selection.copy(members = members), groupFeedback = null)
    }

    fun nameNewGroup(name: String) {
        if (state.busy) return
        state.groupSelection?.let { state = state.copy(groupSelection = it.copy(name = name)) }
    }

    fun saveGroupSelection(target: RecordGroupId?) {
        val selection = state.groupSelection ?: return
        if (state.loading || state.groupReadError != null || selection.members.isEmpty() || (target == null && selection.name.isBlank())) return
        runAction("收录结果未能确认，请再次确认收录。") {
            val groups = requireNotNull(recordGroups)
            val result = withContext(Dispatchers.IO) {
                if (target == null) groups.createGroup(selection.creationId, selection.name, selection.members)
                else groups.appendToGroup(target, selection.members)
            }
            when (result) {
                is GroupUpdate.Saved -> {
                    state = state.copy(groupId = result.id, groupSelection = null, groupContent = null)
                    navigate(Page.GroupDetail)
                }
                is GroupUpdate.UnavailableRecords -> {
                    state = state.copy(groupSelection = selection.copy(members = selection.members - result.ids.toSet()))
                    navigate(Page.GroupPicker)
                    state = state.copy(groupFeedback = "${result.ids.size} 条所选记录已失效，请确认剩余选择。")
                }
                GroupUpdate.MissingGroup -> {
                    state = state.copy(groupSelection = selection.copy(origin = Page.Groups,
                        creationId = if (target == null) RecordGroupId(UUID.randomUUID().toString()) else selection.creationId))
                    navigate(Page.GroupDestination)
                    state = state.copy(groupFeedback = "原记录组已删除，请重新选择记录组。")
                }
                is GroupUpdate.OrderChanged -> error("收录操作返回了调序结果")
            }
        }
    }

    fun moveGroupMember(member: RecordId, direction: MoveDirection) {
        val group = state.groupContent ?: return
        if (state.loading || state.groupReadError != null) return
        val expected = group.members.map { it.id }
        val index = expected.indexOf(member)
        val to = index + if (direction == MoveDirection.UP) -1 else 1
        if (index < 0 || to !in expected.indices) return
        val order = expected.toMutableList().apply { add(to, removeAt(index)) }
        runAction("调序失败，请重试。") {
            val result = withContext(Dispatchers.IO) { requireNotNull(recordGroups).reorderGroup(group.group.id, expected, order) }
            state = state.copy(groupFeedback = when (result) {
                is GroupUpdate.OrderChanged -> "成员顺序已变化，请按最新顺序再调整。"
                GroupUpdate.MissingGroup -> "记录组已删除。"
                is GroupUpdate.Saved -> null
                is GroupUpdate.UnavailableRecords -> error("调序操作返回了收录结果")
            })
            refresh()
        }
    }

    fun removeGroupMember(member: RecordId) {
        val id = state.groupId ?: return
        runAction("移出失败，请重试。") {
            val removed = withContext(Dispatchers.IO) { requireNotNull(recordGroups).removeFromGroup(id, member) }
            state = state.copy(groupFeedback = if (removed) "已移出记录组。" else "成员已不在组内。")
            refresh()
        }
    }

    fun deleteGroup() {
        val id = state.groupId ?: return
        runAction("删除记录组失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(recordGroups).deleteGroup(id) }
            state = state.copy(groupId = null, groupContent = null)
            navigate(Page.Groups)
        }
    }

    internal fun restoreGroupNavigation(page: Page, id: RecordGroupId?, selection: GroupSelection?, detail: RecordId?, origin: Page = Page.GroupDetail) {
        if (state.page != Page.Capture || state.busy) return
        state = state.copy(groupId = id, groupSelection = selection)
        if (page == Page.Detail && detail != null) {
            runAction("记录读取失败，请重试。") { showDetail(detail, origin) }
        } else if (page.isGroupPage) navigate(page)
    }

    private val seasonSettings get() = requireNotNull(times as? SeasonSettings)

    suspend fun seasonMonths() = withContext(Dispatchers.IO) { seasonSettings.readSeasonMonths() }

    suspend fun saveSeasonMonths(season: NamedSeason, months: SeasonMonths) = withContext(Dispatchers.IO) {
        seasonSettings.saveSeasonMonths(mapOf(season to months))
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
    fun filterEventDate(filter: EventDateFilter?) { state = state.copy(eventDateFilter = filter); refresh() }
    fun filterMedia(types: Set<MediaFilter>) { state = state.copy(mediaTypes = types); refresh() }
    fun clearFilters() { state = state.copy(filterTags = emptySet(), starredOnly = false, placeScope = null,
        filterPeople = emptySet(), filterThings = emptySet(), mediaTypes = emptySet(), eventDateFilter = null); refresh() }
    fun openSearch() { state = state.copy(searchOrigin = Page.Records, placeScope = null, linkingPlace = null); home(Page.Search) }
    fun openStarred() { state = state.copy(filterTags = emptySet(), starredOnly = true, query = "", placeScope = null, searchOrigin = Page.Records, linkingPlace = null,
        filterPeople = emptySet(), filterThings = emptySet(), mediaTypes = emptySet(), eventDateFilter = null); home(Page.Search) }
    private val cardSearchOrigin get() = if (state.page.isGroupReadingPage) state.page else Page.Records
    fun openTag(tag: Tag) { state = state.copy(filterTags = setOf(tag.id), starredOnly = false, query = "", placeScope = null, searchOrigin = cardSearchOrigin, linkingPlace = null,
        filterPeople = emptySet(), filterThings = emptySet(), mediaTypes = emptySet(), eventDateFilter = null); home(Page.Search) }

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
        state = state.copy(query = "", filterTags = emptySet(), starredOnly = false, placeScope = null,
            mediaTypes = emptySet(), eventDateFilter = null, filterPeople = if (value.kind == ObjectKind.PERSON) setOf(value.id) else emptySet(),
            filterThings = if (value.kind == ObjectKind.THING) setOf(value.id) else emptySet(), searchOrigin = cardSearchOrigin, linkingPlace = null)
        home(Page.Search)
    }

    private var placesVersion = 0
    private var directoryPlaceQuery = ""
    fun loadPlaces(query: String = "") {
        if (state.page == Page.Places) directoryPlaceQuery = query
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
        state = state.copy(placeScope = PlaceScope(place, isBook = true), query = "", filterTags = emptySet(), starredOnly = false, searchOrigin = Page.Places, linkingPlace = null,
            filterPeople = emptySet(), filterThings = emptySet(), mediaTypes = emptySet(), eventDateFilter = null)
        home(Page.Search)
    }
    fun selectPlaceRecent(value: Boolean) { state = state.copy(placeRecent = value) }
    fun chooseExistingRecord() {
        val place = state.placeBook ?: return
        state = state.copy(linkingPlace = place, placeScope = null, query = "", filterTags = emptySet(), starredOnly = false,
            filterPeople = emptySet(), filterThings = emptySet(), mediaTypes = emptySet(), eventDateFilter = null)
        refresh()
    }
    fun filterByPlace(place: Place?) { state = state.copy(placeScope = place?.let { PlaceScope(it, isBook = false) }); refresh() }
    fun updatePlace(info: PlaceInfo) {
        val place = state.placeBook ?: return
        runAction("地点资料保存失败，请重试。") {
            withContext(Dispatchers.IO) { requireNotNull(places).updatePlace(place.id, info) }
            state = state.copy(placeScope = PlaceScope(place.copy(info = info), isBook = true))
            refresh()
        }
    }
    fun newTextAtPlace() {
        if (state.busy || state.editor != null) return
        val place = state.placeBook ?: return
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
        val context = DeletedRecord(id, origin, state.query, state.filterTags, state.starredOnly, state.recent, state.placeScope, state.searchOrigin, state.placeRecent,
            state.filterPeople, state.filterThings, state.mediaTypes, state.eventDateFilter, state.groupId)
        val hasDraft = state.drafts.any { it.recordId == id }
        media?.stopPlayback()
        runAction("移入回收站失败，请重试。") {
            val moved = withContext(Dispatchers.IO) { requireNotNull(trash).moveToTrash(id) }
            check(moved)
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
            val restored = withContext(Dispatchers.IO) { requireNotNull(trash).restoreRecord(deleted.id) }
            check(restored)
            state = state.copy(page = deleted.origin, deleted = null, notice = "已撤销删除", groupFeedback = null,
                query = deleted.query, filterTags = deleted.filterTags, starredOnly = deleted.starredOnly, recent = deleted.recent,
                placeScope = deleted.placeScope, searchOrigin = deleted.searchOrigin, placeRecent = deleted.placeRecent,
                filterPeople = deleted.filterPeople, filterThings = deleted.filterThings, mediaTypes = deleted.mediaTypes,
                eventDateFilter = deleted.eventDateFilter, groupId = deleted.groupId,
                groupContent = if (deleted.origin == Page.GroupDetail) null else state.groupContent,
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
        val restored = withContext(Dispatchers.IO) { requireNotNull(trash).restoreRecord(id) }
        check(restored)
        state = state.copy(page = Page.Trash, trashDetail = null, restoredId = id, deleted = null, notice = "已恢复")
        refresh()
    }

    fun deletePermanently(id: RecordId) = runAction("彻底删除失败，请重试。") {
        media?.stopPlayback()
        val result = withContext(Dispatchers.IO) { requireNotNull(trash).deletePermanently(id) }
        state = state.copy(cleanupPending = result.pendingPhotoCount != 0 || result.pendingAudioCount != 0 || result.pendingImportCount != 0)
        check(result.deleted)
        state = state.copy(page = Page.Trash, trashDetail = null, restoredId = null, deleted = null,
            notice = "已彻底删除")
        refresh()
    }

    fun retryCleanup() = runAction("部分文件尚未清理，请稍后重试。") {
        val remaining = withContext(Dispatchers.IO) { requireNotNull(trash).retryMediaCleanup() }
        state = state.copy(cleanupPending = remaining > 0)
    }

    fun dismissNotice() { state = state.copy(notice = null, deleted = null, restoredId = null) }

    private fun runAction(failure: String, afterSuccess: (() -> Unit)? = null, action: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, draftReadRetry = null)
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
    is ContentItem.Link -> copy(id = id)
    is ContentItem.Pdf -> copy(id = id)
    is ContentItem.Video -> copy(id = id)
    is ContentItem.Audio -> copy(id = id)
    is ContentItem.Checklist -> copy(id = id)
}
