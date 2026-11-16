package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class EventTimeTest : PhotoStoreFixture() {
    private var now = 1_000L
    private val original = TextEdit("东京夜宵", 4)
    private val event = EventTime(
        LocalDate.of(2026, 11, 8), LocalTime.of(0, 30, 12, 345_000_000), ZoneId.of("Asia/Tokyo"),
    )
    private val dateOnly = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Europe/Paris"))

    @Before
    fun useTestClock() {
        store = LocalTextStore(
            database, LocalPhotoOriginals.get(InstrumentationRegistry.getInstrumentation().targetContext),
            now = { now },
        )
    }

    private fun reopen() {
        reopenDatabase()
        useTestClock()
    }

    private suspend fun publishOriginal(): RecordId =
        store.completeDraft(requireNotNull(store.saveDraft(null, original, event)), original)

    @Test
    fun firstSaveKeepsItsTimeAcrossDaysAndZonesWithoutInventingUnknownEventTimes() = runBlocking {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertNull(store.saveDraft(null, TextEdit("", 0), event))
            assertTrue(store.listDrafts().isEmpty())
            val draft = requireNotNull(store.saveDraft(null, original, event))
            val photo = importPhoto(Color.BLUE)
            assertEquals(DraftTimes(1_000L, event), store.openDraftTimes(draft))

            now += 86_400_000L
            store.saveDraft(draft, TextEdit("东京夜宵，次日续写", 9), dateOnly)
            store.appendPhotos(draft, listOf(photo.id), dateOnly)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            reopen()
            assertEquals(DraftTimes(1_000L, event), store.openDraftTimes(draft))
            val record = store.completeMixedDraft(draft)
            assertEquals(RecordTimes(1_000L, now, event), store.openRecordContent(record)!!.times)

            val unknownText = requireNotNull(store.saveDraft(null, TextEdit("补记旧事", 4)))
            val unknownPhoto = store.appendPhotos(null, listOf(photo.id))
            val confirmedPhoto = store.appendPhotos(null, listOf(photo.id), dateOnly)
            reopen()
            assertEquals(DraftTimes(now, null), store.openDraftTimes(unknownText))
            assertEquals(DraftTimes(now, null), store.openDraftTimes(unknownPhoto))
            assertEquals(DraftTimes(now, dateOnly), store.openDraftTimes(confirmedPhoto))
            val photoRecord = store.completeMixedDraft(unknownPhoto)
            assertNull(store.openRecordContent(photoRecord)!!.times.eventTime)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun timeEditsPublishOnlyOnCompletionAndUnchangedOrDiscardedEditsKeepModifiedTime() = runBlocking {
        val record = publishOriginal()
        val draft = store.editRecord(record)
        assertEquals(DraftTimes(1_000L, event), store.openDraftTimes(draft))
        now = 2_000L
        store.saveDraftEventTime(draft, dateOnly)
        reopen()
        assertEquals(DraftTimes(1_000L, dateOnly), store.openDraftTimes(draft))
        assertEquals(RecordTimes(1_000L, 1_000L, event), store.openRecordContent(record)!!.times)

        now = 3_000L
        assertEquals(record, store.completeMixedDraft(draft))
        val published = RecordTimes(1_000L, 3_000L, dateOnly)
        assertEquals(published, store.openRecordContent(record)!!.times)

        now = 4_000L
        val unchanged = store.editRecord(record)
        store.saveDraftEventTime(unchanged, null)
        store.saveDraftEventTime(unchanged, dateOnly)
        assertEquals(record, store.completeDraft(unchanged, TextEdit(original.text, 0)))
        assertNull(store.openDraftTimes(unchanged))
        assertEquals(published, store.openRecordContent(record)!!.times)

        now = 5_000L
        val discarded = store.editRecord(record)
        store.saveDraftEventTime(discarded, event)
        store.discardDraft(discarded)
        assertNull(store.openDraftTimes(discarded))
        assertEquals(published, store.openRecordContent(record)!!.times)

        now = 6_000L
        val cleared = store.editRecord(record)
        store.saveDraftEventTime(cleared, null)
        store.saveDraft(cleared, original, event)
        reopen()
        assertEquals(DraftTimes(1_000L, null), store.openDraftTimes(cleared))
        assertEquals(published, store.openRecordContent(record)!!.times)
        now = 7_000L
        assertEquals(record, store.completeMixedDraft(cleared))
        assertEquals(RecordTimes(1_000L, 7_000L, null), store.openRecordContent(record)!!.times)
    }

    @Test
    fun failedCompletionRollsBackContentAndTimeTogetherAndRetryIsIdempotent() = runBlocking {
        val record = publishOriginal()
        val draft = store.editRecord(record)
        val saved = TextEdit("草稿夜宵", 2)
        val finalEdit = TextEdit("补记巴黎夜宵", 6)
        now = 2_000L
        store.saveDraft(draft, saved)
        store.saveDraftEventTime(draft, dateOnly)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER reject_time_cleanup BEFORE DELETE ON text_drafts
               BEGIN SELECT RAISE(ABORT, 'test_reject_time_cleanup'); END""",
        )
        try {
            val failure = runCatching { store.completeDraft(draft, finalEdit) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_time_cleanup") == true })
            assertEquals(original.text, store.openRecordContent(record)!!.text)
            assertEquals(RecordTimes(1_000L, 1_000L, event), store.openRecordContent(record)!!.times)
            assertEquals(saved, store.openDraft(draft))
            assertEquals(DraftTimes(1_000L, dateOnly), store.openDraftTimes(draft))
        } finally {
            sql.execSQL("DROP TRIGGER reject_time_cleanup")
        }

        reopen()
        now = 3_000L
        assertEquals(record, store.completeDraft(draft, finalEdit))
        val published = requireNotNull(store.openRecordContent(record))
        assertEquals(finalEdit.text, published.text)
        assertEquals(RecordTimes(1_000L, 3_000L, dateOnly), published.times)
        assertNull(store.openDraftTimes(draft))

        reopen()
        now = 4_000L
        assertEquals(record, store.completeDraft(draft, finalEdit))
        assertEquals(published, store.openRecordContent(record))
        assertEquals(listOf(record), store.search("巴黎").map { it.id })
    }

    @Test
    fun trashAndRestoreKeepPublishedAndDraftTimesSeparate() = runBlocking {
        val record = publishOriginal()
        val draft = store.editRecord(record)
        now = 2_000L
        store.saveDraftEventTime(draft, dateOnly)
        val published = requireNotNull(store.openRecordContent(record))
        now = 3_000L
        assertTrue(store.moveToTrash(record))
        assertNull(store.openRecordContent(record))
        assertNull(store.openDraftTimes(draft))
        assertTrue(runCatching { store.saveDraftEventTime(draft, null) }.isFailure)

        reopen()
        val trashed = requireNotNull(store.openTrashRecord(record))
        assertEquals(published, trashed.content)
        assertEquals(3_000L, trashed.deletedAt)
        assertTrue(trashed.hasDraft)
        now = 4_000L
        assertTrue(store.restoreRecord(record))
        assertEquals(published, store.openRecordContent(record))
        assertEquals(draft, store.editRecord(record))
        assertEquals(DraftTimes(1_000L, dateOnly), store.openDraftTimes(draft))
    }
}
