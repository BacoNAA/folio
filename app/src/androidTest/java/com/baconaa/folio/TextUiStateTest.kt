package com.baconaa.folio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import com.baconaa.folio.ui.Page
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextUiStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = LocalTextStore.get(instrumentation.targetContext)

    @Test
    fun failedSaveAndCompletionKeepInputAndRetryPublishesExactlyOnce() {
        var failSave = true
        var failComplete = true
        val failing = object : TextDrafts by store {
            override suspend fun saveDraft(id: DraftId?, edit: TextEdit, initialEventTime: EventTime?): DraftId? {
                if (failSave) throw IOException("test failure")
                return store.saveDraft(id, edit, initialEventTime)
            }
        }
        val failingMixed = object : MixedContentDrafts by store {
            override suspend fun completeMixedDraft(id: DraftId): RecordId {
                if (failComplete) throw IOException("test failure")
                return store.completeMixedDraft(id)
            }
        }
        lateinit var model: FolioViewModel
        val text = "失败重试${System.nanoTime()}"
        main { model = FolioViewModel(failing, store, failingMixed, store); model.newText(); model.change(TextFieldValue(text)) }
        await { model.state.saveError != null }
        main { assertEquals(text, model.state.editor!!.value.text); failSave = false; model.save() }
        await { model.state.editor?.draftId != null && model.state.saveError == null }
        main { model.complete() }
        await { !model.state.busy && model.state.error != null }
        main { assertEquals(Page.Editor, model.state.page); assertEquals(text, model.state.editor!!.value.text) }
        assertTrue(runBlocking { store.search(text) }.isEmpty())
        main { failComplete = false; model.complete(); model.complete() }
        await { model.state.page == Page.Capture && !model.state.busy }
        assertEquals(1, runBlocking { store.search(text) }.size)
        val id = model.state.savedId!!
        runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
    }

    @Test
    fun completionWaitsForInFlightSaveAndIncludesNewestTextAndCursor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        val delayed = object : TextDrafts by store {
            override suspend fun saveDraft(id: DraftId?, edit: TextEdit, initialEventTime: EventTime?): DraftId? {
                if (first) { first = false; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                return store.saveDraft(id, edit, initialEventTime)
            }
        }
        lateinit var model: FolioViewModel
        val text = "保存顺序${System.nanoTime()}"
        main { model = FolioViewModel(delayed, store, store, store); model.newText(); model.change(TextFieldValue(text)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        main { model.change(TextFieldValue("$text，最后输入", TextRange(2))); model.complete() }
        release.countDown()
        await { model.state.page == Page.Capture && !model.state.busy }
        val id = model.state.savedId!!
        assertEquals("$text，最后输入", runBlocking { store.openRecord(id) })
        assertEquals(1, runBlocking { store.search(text) }.size)
        runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
    }

    @Test
    fun detailReadFailureAfterEditingDoesNotReportCompletionFailure() {
        val text = "详情读取${System.nanoTime()}"
        val id = runBlocking {
            val draft = store.saveDraft(null, TextEdit(text, 0))!!
            store.completeDraft(draft, TextEdit(text, 0))
        }
        var failRead = false
        val failing = object : PublishedTextRecords by store {
            override suspend fun openRecordContent(id: RecordId): RecordContent? {
                if (failRead) throw IOException("test failure")
                return store.openRecordContent(id)
            }
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, failing, store, store); model.openRecord(id) }
        await { model.state.detail != null && !model.state.busy }
        main { model.editRecord() }
        await { model.state.editor != null && !model.state.busy }
        main { model.change(TextFieldValue("$text，修改")); failRead = true; model.complete() }
        await { !model.state.busy && model.state.page == Page.Detail }
        main {
            assertNull(model.state.editor)
            assertEquals("记录读取失败，请重试。", model.state.error)
            failRead = false
            model.retryDetail()
        }
        await { model.state.detail != null && !model.state.busy }
        assertEquals("$text，修改", runBlocking { store.openRecord(id) })
        runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
    }

    @Test
    fun queryFailureIsAnErrorAndRetryCanReturnEmptyResults() {
        var fail = true
        val failing = object : RecordBrowsing by store {
            override suspend fun queryRecords(query: RecordQuery): RecordSearchResult {
                if (fail) throw IOException("test failure")
                return RecordSearchResult(emptyList(), emptyList(), 0)
            }
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, failing); model.home(Page.Search) }
        await { !model.state.loading }
        main { assertNotNull(model.state.error); fail = false; model.refresh() }
        await { !model.state.loading }
        main { assertNull(model.state.error); assertTrue(model.state.results.isEmpty()) }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("UI state did not settle before timeout")
    }
}
