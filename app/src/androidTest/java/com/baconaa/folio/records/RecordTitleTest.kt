package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class RecordTitleTest : PhotoStoreFixture() {
    private var now = 1_000L

    @Before
    fun useTestClock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LocalTextStore(database, LocalPhotoOriginals.get(context), { now }, LocalAudioOriginals.get(context))
    }

    @Test
    fun titleCanStartARecoverableDraftAndFirstContentSetsTheRecordTime() = runBlocking {
        assertNull(store.saveDraftTitle(null, "  "))
        val title = "沿河的老屋"
        val draft = requireNotNull(store.saveDraftTitle(null, title))
        reopenDatabase()
        useTestClock()
        assertEquals(title, store.listDrafts().single().title)
        assertEquals(title, store.openMixedDraft(draft)!!.title)
        assertNull(store.openDraftTimes(draft)!!.firstRecordedAt)
        assertTrue(runCatching { store.completeMixedDraft(draft) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.search(title).isEmpty())

        now = 2_000L
        val photo = importPhoto(Color.GREEN)
        store.appendPhotos(draft, listOf(photo.id))
        assertEquals(2_000L, store.openDraftTimes(draft)!!.firstRecordedAt)
        now = 3_000L
        val record = store.completeMixedDraft(draft)
        val content = store.openRecordContent(record)!!
        assertEquals(title, content.title)
        assertEquals(listOf(photo.id), content.photos)
        assertEquals(RecordTimes(2_000L, 3_000L, null), content.times)
        assertEquals(title, store.search("老屋").single().title)
        assertEquals(title, store.queryRecords("老屋").single().title)
    }

    @Test
    fun clearingTheLastTitleDropsOnlyAnOtherwiseEmptyDraft() = runBlocking {
        val empty = requireNotNull(store.saveDraftTitle(null, "临时标题"))
        assertNull(store.saveDraftTitle(empty, null))
        assertTrue(store.listDrafts().isEmpty())

        val draft = requireNotNull(store.saveDraftTitle(null, "带线索的草稿"))
        val place = store.createDraftPlace(draft, PlaceInfo("巷口"))
        assertEquals(draft, store.saveDraftTitle(draft, "  "))
        assertNull(store.openMixedDraft(draft)!!.title)
        assertEquals(place, store.openDraftPlace(draft)!!.id)
        assertNull(store.openDraftTimes(draft)!!.firstRecordedAt)
    }

    @Test
    fun editedTitleIsPrivateUntilCompletionAndRestoresSeparatelyFromThePublishedTitle() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val draft = store.appendPhotos(null, listOf(photo.id))
        val record = store.completeMixedDraft(draft)
        assertNull(store.openRecordContent(record)!!.title)

        val edit = store.editRecord(record)
        val title = "外公的相册"
        store.saveDraftTitle(edit, title)
        assertTrue(store.queryRecords(title).isEmpty())
        now = 2_000L
        assertEquals(record, store.completeMixedDraft(edit))
        val published = store.openRecordContent(record)!!
        assertEquals(title, published.title)
        assertEquals(RecordTimes(1_000L, 2_000L, null), published.times)

        val pending = store.editRecord(record)
        store.saveDraftTitle(pending, "另一册")
        store.moveToTrash(record)
        assertTrue(store.search(title).isEmpty())
        assertEquals(title, store.listTrash().single().title)
        assertEquals(published, store.openTrashRecord(record)!!.content)
        store.restoreRecord(record)
        assertEquals("另一册", store.openMixedDraft(pending)!!.title)
        assertEquals(title, store.queryRecords(title).single().title)
        assertTrue(store.queryRecords("另一册").isEmpty())

        now = 3_000L
        store.saveDraftTitle(pending, title)
        store.completeMixedDraft(pending)
        assertEquals(published, store.openRecordContent(record))
        val cleared = store.editRecord(record)
        store.saveDraftTitle(cleared, " \t ")
        assertNull(store.openMixedDraft(cleared)!!.title)
        store.completeMixedDraft(cleared)
        assertTrue(store.search(title).isEmpty())
        assertNull(store.queryRecords().single().title)
        val withoutTitle = store.openRecordContent(record)!!
        assertEquals(listOf(photo.id), withoutTitle.photos)
        assertEquals(RecordTimes(1_000L, 3_000L, null), withoutTitle.times)
    }

    @Test
    fun firstTextSubmittedWithCompleteIsSavedWithTheTitleExactlyOnce() = runBlocking {
        val draft = requireNotNull(store.saveDraftTitle(null, "城南"))
        now = 2_000L
        val text = TextEdit("重访旧街", 4)
        val record = store.completeDraft(draft, text)
        now = 3_000L
        assertEquals(record, store.completeDraft(draft, text))
        val content = store.openRecordContent(record)!!
        assertEquals("城南", content.title)
        assertEquals(text.text, content.text)
        assertEquals(RecordTimes(2_000L, 2_000L, null), content.times)
    }
}
