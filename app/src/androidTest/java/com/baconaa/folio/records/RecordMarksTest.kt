package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordMarksTest : PhotoStoreFixture() {
    private var now = 1_000L

    @Before
    fun useTestClock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LocalTextStore(database, context) { now }
    }

    private fun reopen() {
        reopenDatabase()
        useTestClock()
    }

    private suspend fun publish(text: String = "记录线索", at: Long = now, date: String? = null): RecordId {
        now = at
        val edit = TextEdit(text, text.length)
        val event = date?.let { EventTime(LocalDate.parse(it), zone = ZoneId.of("Asia/Shanghai")) }
        return store.completeDraft(requireNotNull(store.saveDraft(null, edit, event)), edit)
    }

    @Test
    fun independentMarksPersistWithoutChangingContentTimesOrRevision() = runBlocking {
        assertTrue(store.listStarredRecords().isEmpty())
        val record = publish()
        val before = store.openRecordContent(record)!!
        val revision = database.textDao().activeRecord(record.value)!!.revision
        assertEquals(RecordMarks(), before.marks)
        now = 2_000L
        store.setStarred(record, true)
        store.setPinned(record, true)
        store.setStarred(record, true)
        store.setPinned(record, true)
        reopen()
        val marked = before.copy(marks = RecordMarks(true, true))
        assertEquals(marked, store.openRecordContent(record))
        assertEquals(revision, database.textDao().activeRecord(record.value)!!.revision)
        assertEquals(marked.marks, store.search("线索").single().marks)
        assertEquals(marked.marks, store.listRecentRecords().single().marks)
        assertEquals(store.listRecentRecords(), store.listStarredRecords())

        store.setStarred(record, false)
        assertEquals(RecordMarks(false, true), store.openRecordContent(record)!!.marks)
        assertTrue(store.listStarredRecords().isEmpty())
        store.setStarred(record, true)
        store.setPinned(record, false)
        assertEquals(RecordMarks(true, false), store.openRecordContent(record)!!.marks)
        store.setStarred(record, false)
        assertEquals(before, store.openRecordContent(record))
        assertEquals(revision, database.textDao().activeRecord(record.value)!!.revision)
    }

    @Test
    fun finishingRetryingOrDiscardingAnOlderDraftKeepsCurrentMarks() = runBlocking {
        val record = publish()
        val before = store.openRecordContent(record)!!
        val draft = store.editRecord(record)
        store.saveDraft(draft, TextEdit("补充线索", 4))
        now = 2_000L
        store.setStarred(record, true)
        store.setPinned(record, true)
        assertEquals(before.copy(marks = RecordMarks(true, true)), store.openRecordContent(record))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_marked_completion BEFORE DELETE ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'test_reject_marked_completion'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(draft) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_marked_completion") == true })
            assertEquals(before.copy(marks = RecordMarks(true, true)), store.openRecordContent(record))
            assertEquals(TextEdit("补充线索", 4), store.openDraft(draft))
        } finally {
            sql.execSQL("DROP TRIGGER reject_marked_completion")
        }
        reopen()
        assertEquals(record, store.completeMixedDraft(draft))
        val completed = store.openRecordContent(record)!!
        assertEquals("补充线索", completed.text)
        assertEquals(RecordMarks(true, true), completed.marks)
        assertEquals(2_000L, completed.times.modifiedAt)
        assertEquals(record, store.completeMixedDraft(draft))

        val discarded = store.editRecord(record)
        store.saveDraft(discarded, TextEdit("不保留", 3))
        store.setStarred(record, false)
        store.setPinned(record, false)
        now = 3_000L
        store.discardDraft(discarded)
        assertEquals(completed.copy(marks = RecordMarks()), store.openRecordContent(record))
        val unchanged = store.editRecord(record)
        store.setPinned(record, true)
        store.completeMixedDraft(unchanged)
        assertEquals(completed.copy(marks = RecordMarks(false, true)), store.openRecordContent(record))
    }

    @Test
    fun pinnedRecordsLeadWithinTheirGroupsAndStarFilterKeepsPublishedOrdering() = runBlocking {
        val january = publish("一月线索", 100L, "2026-01-01")
        val januaryLater = publish("同日线索", 100L, "2026-01-01")
        val december = publish("十二月线索", 200L, "2026-12-01")
        val priorYear = publish("旧年线索", 300L, "2025-12-31")
        val undatedPinned = publish("未定线索", 400L)
        val undatedRecent = publish("最近线索", 500L)
        store.saveDraft(null, TextEdit("草稿线索", 4))
        listOf(january, januaryLater, priorYear, undatedPinned).forEach { store.setPinned(it, true) }
        listOf(january, december, undatedPinned).forEach { store.setStarred(it, true) }
        reopen()
        val recent = store.listRecentRecords()
        assertEquals(listOf(undatedPinned, priorYear, januaryLater, january, undatedRecent, december), recent.map { it.id })
        assertEquals(recent.map { it.id }, store.search("线索").map { it.id })
        assertEquals(recent.filter { it.marks.isStarred }, store.listStarredRecords())
        val timeline = store.listEventTimeline()
        assertEquals(listOf(2026, 2025), timeline.years.map { it.year })
        assertEquals(listOf(januaryLater, january, december), timeline.years[0].records.map { it.id })
        assertEquals(listOf(priorYear), timeline.years[1].records.map { it.id })
        assertEquals(listOf(undatedPinned, undatedRecent), timeline.undated.map { it.id })

        store.setPinned(january, false)
        assertEquals(listOf(januaryLater, december, january), store.listEventTimeline().years[0].records.map { it.id })
        val beforeStarChange = store.listRecentRecords().map { it.id }
        store.setStarred(undatedRecent, true)
        assertEquals(beforeStarChange, store.listRecentRecords().map { it.id })
    }

    @Test
    fun trashIsReadOnlyAndRestoresMarksWithTheUnfinishedDraft() = runBlocking {
        val record = publish()
        store.setStarred(record, true)
        store.setPinned(record, true)
        val content = store.openRecordContent(record)!!
        val draft = store.editRecord(record)
        store.saveDraft(draft, TextEdit("待完成线索", 5))
        assertTrue(store.moveToTrash(record))
        reopen()
        assertTrue(store.listStarredRecords().isEmpty())
        assertTrue(store.search("线索").isEmpty())
        assertEquals(content, store.openTrashRecord(record)!!.content)
        assertEquals(content.marks, store.listTrash().single().marks)
        assertTrue(runCatching { store.setStarred(record, false) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { store.setPinned(record, false) }.exceptionOrNull() is IllegalStateException)
        assertEquals(content, store.openTrashRecord(record)!!.content)
        assertTrue(store.restoreRecord(record))
        assertEquals(content, store.openRecordContent(record))
        assertEquals(TextEdit("待完成线索", 5), store.openDraft(draft))
        assertEquals(listOf(record), store.listStarredRecords().map { it.id })
        store.moveToTrash(record)
        store.deletePermanently(record)
        assertNull(store.openRecordContent(record))
        assertTrue(store.listStarredRecords().isEmpty())
        assertFalse(store.restoreRecord(record))
        assertTrue(runCatching { store.setStarred(record, true) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { store.setPinned(record, true) }.exceptionOrNull() is IllegalStateException)
    }
}
