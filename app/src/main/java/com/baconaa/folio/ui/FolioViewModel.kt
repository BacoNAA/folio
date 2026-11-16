package com.baconaa.folio.ui

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

data class TextEditor(
    val draftId: DraftId? = null,
    val recordId: RecordId? = null,
    val value: TextFieldValue = TextFieldValue(),
    val revision: Long = 0,
    val savedRevision: Long = 0,
    val returnPage: Page = Page.Capture,
)

data class FolioState(
    val page: Page = Page.Capture,
    val editor: TextEditor? = null,
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
)

/** UI 状态由主线程持有；草稿写入串行，完成和离开等待最后一次输入落盘。 */
class FolioViewModel(
    private val drafts: TextDrafts,
    private val published: PublishedTextRecords,
    private val mixed: MixedContentDrafts,
    private val browsing: RecordBrowsing,
) : ViewModel() {
    var state by mutableStateOf(FolioState())
        private set
    private val writes = Mutex()
    private var loadVersion = 0

    fun home(page: Page) {
        if (state.busy || state.editor != null) return
        loadVersion++
        state = state.copy(page = page, error = null, loading = false)
        if (page == Page.Records || page == Page.Search) refresh()
    }

    fun newText() {
        if (state.busy) return
        loadVersion++
        state = state.copy(page = Page.Editor, editor = TextEditor(), savedId = null,
            error = null, saveError = null, loading = false)
    }

    fun change(value: TextFieldValue) {
        val editor = state.editor ?: return
        if (state.busy) return
        // 输入法组合态不落盘，只持久化正文及选区末端。
        if (value.text == editor.value.text && value.selection == editor.value.selection) {
            state = state.copy(editor = editor.copy(value = value))
            return
        }
        state = state.copy(editor = editor.copy(value = value, revision = editor.revision + 1), saveError = null)
        save()
    }

    fun save() {
        if (state.editor == null) return
        viewModelScope.launch { writes.withLock { saveLatest() } }
    }

    private suspend fun saveLatest(): Boolean {
        while (true) {
            val snapshot = state.editor ?: return true
            if (snapshot.revision == snapshot.savedRevision) return true
            try {
                val id = withContext(Dispatchers.IO) {
                    drafts.saveDraft(snapshot.draftId, snapshot.value.asEdit())
                }
                // Room 写入时仍可输入：保留较新的输入，只确认已成功落盘的版本。
                val current = state.editor ?: return true
                state = state.copy(editor = current.copy(draftId = id, savedRevision = snapshot.revision), saveError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(saveError = "草稿保存失败，请重试。当前输入仍保留在页面中。")
                return false
            }
        }
    }

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
        // 本批只编辑单段文字，不能通过旧文字接口把已有混合内容压平成一段。
        if (content.items.size != 1 || content.items.single() !is ContentItem.Text) {
            state = state.copy(error = "这份草稿包含多项内容，需在后续混合编辑页面中续写。")
            return
        }
        val text = (content.items.single() as ContentItem.Text).text
        loadVersion++
        state = state.copy(page = Page.Editor, loading = false, error = null, saveError = null,
            detailOrigin = if (recordId != null && origin != Page.Detail) origin else state.detailOrigin,
            editor = TextEditor(id, recordId, TextFieldValue(text, TextRange(content.cursor)), returnPage = origin))
    }

    fun complete() {
        if (state.editor?.value?.text.isNullOrBlank()) return
        runAction("完成失败，草稿仍保留，请重试。") {
            writes.withLock {
                if (!saveLatest()) return@withLock
                val editor = state.editor ?: return@withLock
                val id = editor.draftId ?: return@withLock
                val recordId = withContext(Dispatchers.IO) { drafts.completeDraft(id, editor.value.asEdit()) }
                state = state.copy(editor = null, saveError = null, savedId = recordId, page = Page.Capture)
                if (editor.recordId != null) showDetail(recordId, state.detailOrigin)
            }
        }
    }

    fun leaveEditor() {
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
        runAction("放弃修改失败，草稿仍保留，请重试。") {
            writes.withLock {
                val editor = state.editor ?: return@withLock
                editor.draftId?.let { withContext(Dispatchers.IO) { drafts.discardDraft(it) } }
                state = state.copy(editor = null, page = editor.returnPage, saveError = null)
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

    private fun runAction(failure: String, action: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(error = failure) }
            finally { state = state.copy(busy = false) }
        }
    }

    companion object {
        fun canEdit(content: RecordContent?): Boolean =
            content?.items?.let { it.size == 1 && it.single() is ContentItem.Text } == true
    }
}

private fun TextFieldValue.asEdit() = TextEdit(text, selection.end.coerceIn(0, text.length))
