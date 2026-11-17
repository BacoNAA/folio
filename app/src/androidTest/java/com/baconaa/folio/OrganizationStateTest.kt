package com.baconaa.folio

import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.*
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = LocalTextStore.get(instrumentation.targetContext)
    private fun model() = FolioViewModel(store, store, store, store)

    @Test
    fun timeAndTagsResumePrivatelyUntilCompleteAndMarksSurviveDiscard() {
        lateinit var model: FolioViewModel
        val text = "整理验收${System.nanoTime()}"
        val date = EventTime(LocalDate.of(2021, 11, 8), LocalTime.of(0, 30), ZoneId.of("Asia/Tokyo"))
        main { model = model(); model.newText(); model.changeEventTime(date); model.change(TextFieldValue(text)) }
        saved(model)
        main { model.createTag(text) }
        idle(model)
        val tag = model.state.editor!!.tags.single()
        assertFalse(runBlocking { store.listTags() }.any { it.id == tag.id })
        val draft = model.state.editor!!.draftId!!
        main { model.leaveEditor() }
        idle(model)
        main { model = model(); model.resume(runBlocking { store.listDrafts() }.single { it.id == draft }) }
        idle(model)
        assertEquals(date, model.state.editor!!.eventTime)
        assertEquals(tag, model.state.editor!!.tags.single())
        main { model.complete() }
        idle(model)
        val record = model.state.savedId!!
        main { model.openRecord(record) }
        idle(model)
        val original = model.state.detail!!
        assertEquals(date, original.times.eventTime)
        main { model.setStarred(true) }; idle(model)
        main { model.setPinned(true) }; idle(model)
        assertEquals(original.times.modifiedAt, model.state.detail!!.times.modifiedAt)
        main { model.editRecord() }; idle(model)
        main { model.changeEventTime(null); model.selectTag(tag, false) }; idle(model)
        main { model.leaveEditor() }; idle(model)
        assertEquals(date, model.state.detail!!.times.eventTime)
        assertEquals(listOf(tag), model.state.detail!!.tags)
        main { model.editRecord() }; idle(model)
        assertNull(model.state.editor!!.eventTime)
        assertTrue(model.state.editor!!.tags.isEmpty())
        main { model.discard() }; idle(model)
        assertTrue(model.state.detail!!.marks.isStarred && model.state.detail!!.marks.isPinned)
        main { model.home(Page.Search); model.query(text); model.filterByTags(setOf(tag.id)); model.filterStarred(true) }
        await { !model.state.loading }
        assertEquals(listOf(record), model.state.results.map { it.id })
        main { model.clearFilters() }
        await { !model.state.loading }
        assertEquals(text, model.state.query)
        clean(record)
    }

    @Test
    fun clearingTimeWhileFirstSaveIsInFlightDoesNotRestoreTheOldDefault() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delayed = object : TextDrafts by store {
            override suspend fun saveDraft(id: DraftId?, edit: TextEdit, initialEventTime: EventTime?): DraftId? {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return store.saveDraft(id, edit, initialEventTime)
            }
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(delayed, store, store, store); model.newText(); model.change(TextFieldValue("清空默认时间")) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { main { model.changeEventTime(null) } } finally { release.countDown() }
        saved(model)
        assertNull(runBlocking { store.openDraftTimes(model.state.editor!!.draftId!!) }!!.eventTime)
        main { model.discard() }; idle(model)
    }

    @Test
    fun failedTimeSaveBlocksCompletionAndTagReloadFailureDoesNotDuplicateCreation() {
        var failTime = true
        var failRead = false
        val times = object : RecordTimeEditing by store {
            override suspend fun saveDraftEventTime(id: DraftId, eventTime: EventTime?) {
                if (failTime) throw IOException("time write failure")
                store.saveDraftEventTime(id, eventTime)
            }
        }
        val tags = object : RecordTags by store {
            override suspend fun openDraftTags(id: DraftId): List<Tag>? {
                if (failRead) throw IOException("read failure")
                return store.openDraftTags(id)
            }
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store, times = times, tags = tags); model.newText(); model.change(TextFieldValue("故障重试")) }
        saved(model)
        main { model.changeEventTime(null); model.complete() }
        await { !model.state.busy && model.state.saveError != null }
        assertNotNull(model.state.editor)
        assertNull(model.state.savedId)
        main { failTime = false; model.save() }; saved(model)
        main { failRead = true; model.createTag("只创建一次") }; idle(model)
        assertTrue(model.state.editor!!.needsReload)
        val id = model.state.editor!!.draftId!!
        assertEquals(1, runBlocking { store.openDraftTags(id) }!!.size)
        main { model.createTag("只创建一次"); failRead = false; model.reload() }; idle(model)
        assertEquals(1, model.state.editor!!.tags.size)
        main { model.discard() }; idle(model)
    }

    @Test
    fun deleteUndoAndTrashRestoreKeepSearchFiltersAndSeparateUnfinishedDraft() {
        val text = "删除恢复${System.nanoTime()}"
        val id = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit(text, 0))!!) }
        val draft = runBlocking { store.editRecord(id) }
        runBlocking { store.saveDraft(draft, TextEdit("$text，尚未完成", 0)); store.setStarred(id, true) }
        var failDelete = true
        var failRestore = true
        var failCleanup = true
        val trash = object : RecordTrash by store {
            override suspend fun moveToTrash(id: RecordId): Boolean {
                if (failDelete) throw IOException("delete failure")
                return store.moveToTrash(id)
            }
            override suspend fun restoreRecord(id: RecordId): Boolean {
                if (failRestore) throw IOException("restore failure")
                return store.restoreRecord(id)
            }
            override suspend fun retryPhotoCleanup(): Int = if (failCleanup) 1 else store.retryPhotoCleanup()
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store, trash = trash); model.home(Page.Search); model.query(text); model.filterStarred(true) }
        await { !model.state.loading }
        main { model.openRecord(id) }; idle(model)
        main { model.deleteRecord() }; idle(model)
        assertEquals(Page.Detail, model.state.page)
        assertNotNull(model.state.error)
        main { failDelete = false; model.deleteRecord() }; idle(model)
        await { !model.state.loading }
        assertEquals(Page.Search, model.state.page)
        assertEquals(text, model.state.query)
        assertTrue(model.state.starredOnly && model.state.results.isEmpty())
        assertFalse(runBlocking { store.listDrafts() }.any { it.id == draft })
        main { model.undoDelete() }; idle(model)
        assertNotNull(model.state.deleted)
        main { model.query("临时改过的搜索词"); model.filterStarred(false) }
        await { !model.state.loading }
        main { failRestore = false; model.undoDelete() }; idle(model)
        await { !model.state.loading }
        assertEquals(listOf(id), model.state.results.map { it.id })
        assertEquals(text, model.state.query)
        assertTrue(model.state.starredOnly)
        assertTrue(runBlocking { store.listDrafts() }.any { it.id == draft })
        main { model.openRecord(id) }; idle(model)
        main { model.deleteRecord() }; idle(model)
        main { model.home(Page.Trash) }; await { !model.state.loading }
        main { model.openTrashRecord(id) }; idle(model)
        assertTrue(model.state.trashDetail!!.hasDraft)
        assertEquals(text, (model.state.trashDetail!!.content.items.single() as ContentItem.Text).text)
        main { model.restoreRecord(id) }; idle(model)
        assertEquals(Page.Trash, model.state.page)
        assertEquals(id, model.state.restoredId)
        main { model.openRecord(id) }; idle(model)
        assertEquals(Page.Trash, model.state.detailOrigin)
        main { model.deleteRecord() }; idle(model)
        main { model.openTrashRecord(id) }; idle(model)
        main { model.deletePermanently(id) }; idle(model)
        await { !model.state.loading }
        assertNull(runBlocking { store.openTrashRecord(id) })
        assertNull(runBlocking { store.openDraft(draft) })
        assertTrue(model.state.cleanupPending)
        main { failCleanup = false; model.retryCleanup() }; idle(model)
        assertFalse(model.state.cleanupPending)
    }

    @Test
    fun placeReadFailureDoesNotRepeatCreationAndPlaceQueryFailureIsNotAnEmptyList() {
        var failRead = false
        var failList = false
        val places = object : RecordPlaces by store {
            override suspend fun openDraftPlace(id: DraftId): Place? {
                if (failRead) throw IOException("place read failure")
                return store.openDraftPlace(id)
            }
            override suspend fun listPlaces(keyword: String): List<PlaceSummary> {
                if (failList) throw IOException("place list failure")
                return store.listPlaces(keyword)
            }
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store, places = places); model.newText(); model.change(TextFieldValue("地点读取重试")) }
        saved(model)
        main { failRead = true; model.createPlace(PlaceInfo("保留一次")) }; idle(model)
        assertTrue(model.state.editor!!.needsReload)
        val draft = model.state.editor!!.draftId!!
        val place = runBlocking { store.openDraftPlace(draft) }!!
        main { model.createPlace(PlaceInfo("不能重复")); failRead = false; model.reload() }; idle(model)
        assertEquals(place, model.state.editor!!.place)
        main { model.discard() }; idle(model)
        main { failList = true; model.home(Page.Places) }
        await { !model.state.placesLoading }
        assertNotNull(model.state.placesError)
        main { failList = false; model.loadPlaces() }
        await { !model.state.placesLoading }
        assertNull(model.state.placesError)
        val seed = runBlocking { store.saveDraft(null, TextEdit("关联读取重试", 0))!! }
        val shared = runBlocking { store.createDraftPlace(seed, PlaceInfo("已有地点")) }
        val record = runBlocking { store.completeMixedDraft(seed) }
        main { model.openPlace(runBlocking { store.openPlace(shared) }!!); model.chooseExistingRecord() }
        await { !model.state.loading }
        main { failRead = true; model.openRecord(record) }; idle(model)
        assertTrue(model.state.editor!!.needsReload)
        assertEquals(Page.Editor, model.state.page)
        main { failRead = false; model.reload() }; idle(model)
        assertEquals(shared, model.state.editor!!.place!!.id)
        assertEquals(shared, model.state.filterPlace!!.id)
        main { model.discard() }; idle(model)
        clean(record)
    }

    private fun clean(id: RecordId) = runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun idle(model: FolioViewModel) = await { !model.state.busy }
    private fun saved(model: FolioViewModel) = await { model.state.editor?.let { it.draftId != null && it.revision == it.savedRevision } == true && model.state.saveError == null }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("State did not settle")
    }
}
