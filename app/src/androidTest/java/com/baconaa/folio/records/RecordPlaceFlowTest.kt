package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordPlaceFlowTest : PhotoStoreFixture() {
    @Test fun privatePlacesPublishByIdentityAndSharedChangesDoNotOverwriteDrafts() = runBlocking {
        val draft = text("第一杯")
        val other = text("第二杯")
        val west = store.createDraftPlace(draft, PlaceInfo("咖啡", "成都", "西街", "靠河"))
        val east = store.createDraftPlace(other, PlaceInfo("咖啡", "成都", "东街"))
        assertNotEquals(west, east)
        assertTrue(store.listPlaces().isEmpty())
        assertTrue(runCatching { store.selectDraftPlace(other, west) }.isFailure)
        assertTrue(runCatching { store.updatePlace(west, PlaceInfo("不能共享")) }.isFailure)
        reopenDatabase()
        assertEquals(west, store.openDraftPlace(draft)!!.id)
        val record = store.completeMixedDraft(draft)
        val original = store.openRecordContent(record)!!
        val edit = store.editRecord(record)
        val second = store.completeMixedDraft(other)
        store.updatePlace(west, PlaceInfo("老咖啡", "成都", "西街", "院子"))
        assertEquals(original.times, store.openRecordContent(record)!!.times)
        assertEquals("老咖啡", store.openDraftPlace(edit)!!.info.name)
        store.completeMixedDraft(edit)
        assertEquals("老咖啡", store.openRecordContent(record)!!.place!!.info.name)
        assertEquals(listOf(record), store.queryRecords("老咖啡").map { it.id })
        assertEquals(listOf(west), store.listPlaces("院子").map { it.place.id })
        assertEquals(listOf(second), store.queryRecords(placeId = east).map { it.id })
        val change = store.editRecord(record)
        store.selectDraftPlace(change, east)
        assertEquals(listOf(record), store.queryRecords(placeId = west).map { it.id })
        store.completeMixedDraft(change)
        assertTrue(store.queryRecords(placeId = west).isEmpty())
        assertEquals(0, store.listPlaces().single { it.place.id == west }.recordCount)
        val clear = store.editRecord(record)
        store.selectDraftPlace(clear, null)
        store.completeMixedDraft(clear)
        assertNull(store.openRecordContent(record)!!.place)
        assertEquals(1, store.listPlaces().single { it.place.id == east }.recordCount)
    }

    @Test fun failedCompletionRollsBackPlacePromotionAndTrashKeepsBothVersions() = runBlocking {
        val draft = text("旧地点")
        val old = store.createDraftPlace(draft, PlaceInfo("老店"))
        val record = store.completeMixedDraft(draft)
        val edit = store.editRecord(record)
        val ignored = store.createDraftPlace(edit, PlaceInfo("未选中"))
        val pending = store.createDraftPlace(edit, PlaceInfo("新店"))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_place BEFORE DELETE ON text_drafts BEGIN SELECT RAISE(ABORT, 'place_failure'); END")
        try {
            assertTrue(runCatching { store.completeMixedDraft(edit) }.isFailure)
            assertEquals(old, store.openRecordContent(record)!!.place!!.id)
            assertNull(store.openPlace(pending))
            assertEquals(pending, store.openDraftPlace(edit)!!.id)
        } finally { sql.execSQL("DROP TRIGGER reject_place") }
        store.moveToTrash(record)
        assertTrue(store.queryRecords(placeId = old).isEmpty())
        assertTrue(runCatching { store.selectDraftPlace(edit, null) }.isFailure)
        assertEquals(old, store.openTrashRecord(record)!!.content.place!!.id)
        reopenDatabase()
        store.restoreRecord(record)
        assertEquals(pending, store.openDraftPlace(edit)!!.id)
        assertEquals(record, store.completeMixedDraft(edit))
        assertEquals(record, store.completeMixedDraft(edit))
        assertNull(database.placeDao().place(ignored.value))
        assertEquals(pending, store.openRecordContent(record)!!.place!!.id)
        val discarded = store.editRecord(record)
        val private = store.createDraftPlace(discarded, PlaceInfo("待删私有"))
        store.moveToTrash(record)
        store.deletePermanently(record)
        assertNull(database.placeDao().place(private.value))
        assertNotNull(store.openPlace(old))
        assertNotNull(store.openPlace(pending))
        assertTrue(store.listPlaces().all { it.recordCount == 0 })
    }
    private suspend fun text(value: String) = store.saveDraft(null, TextEdit(value, 0))!!

    @Test fun coordinatesPersistAndSummaryUsesOnlyActiveCompletedEventTimes() = runBlocking {
        val first = text("旧事补记")
        val point = PlaceCoordinates(30.67, 104.06)
        val place = store.createDraftPlace(first, PlaceInfo("河边", coordinates = point))
        val latest = EventTime(java.time.LocalDate.of(2024, 5, 1))
        store.saveDraftEventTime(first, latest)
        reopenDatabase()
        assertEquals(point, store.openDraftPlace(first)!!.info.coordinates)
        val record = store.completeMixedDraft(first)
        val edit = store.editRecord(record)
        store.saveDraftEventTime(edit, EventTime(java.time.LocalDate.of(2030, 1, 1)))
        val second = text("后来才补记更早的事")
        store.selectDraftPlace(second, place)
        store.saveDraftEventTime(second, EventTime(java.time.LocalDate.of(2020, 1, 1)))
        store.completeMixedDraft(second)
        assertEquals(RecentExperience.Known(latest), store.listPlaces().single().recentExperience)
        val unknown = text("不确定时间")
        store.selectDraftPlace(unknown, place)
        val unknownId = store.completeMixedDraft(unknown)
        assertEquals(RecentExperience.Uncertain(1), store.listPlaces().single().recentExperience)
        store.moveToTrash(unknownId)
        assertEquals(2, store.listPlaces().single().recordCount)
        assertEquals(RecentExperience.Known(latest), store.listPlaces().single().recentExperience)
        store.restoreRecord(unknownId)
        assertEquals(RecentExperience.Uncertain(1), store.listPlaces().single().recentExperience)
        val originalTimes = store.openRecordContent(record)!!.times
        store.updatePlace(place, PlaceInfo("新河边", coordinates = null))
        store.completeMixedDraft(edit)
        reopenDatabase()
        assertNull(store.openRecordContent(record)!!.place!!.info.coordinates)
        assertEquals(originalTimes.firstRecordedAt, store.openRecordContent(record)!!.times.firstRecordedAt)
        assertEquals("新河边", store.openRecordContent(record)!!.place!!.info.name)
    }
}
