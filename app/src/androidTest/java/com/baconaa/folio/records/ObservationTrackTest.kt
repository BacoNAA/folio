package com.baconaa.folio.records

import android.graphics.Color
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class ObservationTrackTest : PhotoStoreFixture() {
    @Test fun reopenedTrackSortsActualDatesAndRetainsOnlyTheSelectedItemForEachOwner() = runBlocking {
        val june = store.saveDraft(null, TextEdit("重新开张", 0), EventTime.Exact(LocalDate.of(2024, 6, 12)))!!
        val place = store.createDraftPlace(june, PlaceInfo("老面馆"))
        val juneRecord = store.completeMixedDraft(june)
        val march = store.saveDraft(null, TextEdit("装修之前", 0), EventTime.Exact(LocalDate.of(2024, 3, 2)))!!
        store.selectDraftPlace(march, place)
        val marchRecord = store.completeMixedDraft(march)
        val owner = TrackOwner(store.openPlace(place)!!)
        val juneItem = store.openRecordContent(juneRecord)!!.items.single().id
        val marchItem = store.openRecordContent(marchRecord)!!.items.single().id
        store.saveObservation(owner, juneRecord, juneItem, "又能吃面了")
        store.saveObservation(owner, marchRecord, marchItem, "老招牌")
        store.saveObservation(owner, marchRecord, marchItem, "旧招牌")
        reopenDatabase()
        assertEquals(listOf("旧招牌", "又能吃面了"), store.readObservations(owner).map { it.note })
        store.moveToTrash(juneRecord)
        assertEquals(listOf(marchRecord), store.readObservations(owner).map { it.record })
        assertTrue(runCatching { store.saveObservation(owner, juneRecord, juneItem, "无效") }.isFailure)
        store.restoreRecord(juneRecord)
        assertEquals(listOf(marchRecord, juneRecord), store.readObservations(owner).map { it.record })
        store.removeObservation(owner, marchRecord, marchItem)
        assertEquals("装修之前", store.openRecordContent(marchRecord)!!.text)

        val month = EventTime.Month(YearMonth.of(2025, 2))
        val flower = store.saveDraft(null, TextEdit("花盆", 0), month)!!
        val image = importPhoto(Color.GREEN)
        val picture = store.insertPhoto(flower, null, image.id, "绿叶")
        store.createDraftObject(flower, ObjectKind.THING, ObjectInfo("阳台花"))
        val flowerRecord = store.completeMixedDraft(flower)
        val flowerOwner = TrackOwner(store.openRecordContent(flowerRecord)!!.objects.single())
        assertTrue(runCatching { store.saveObservation(owner, flowerRecord, picture, "选错地点") }.isFailure)
        store.saveObservation(flowerOwner, flowerRecord, picture, "买回来")
        reopenDatabase()
        assertEquals(month, store.readObservations(flowerOwner).single().eventTime)
        store.removeObservation(flowerOwner, flowerRecord, picture)
        assertPhotos(store.openRecordContent(flowerRecord)!!.photos, image)
        store.saveObservation(flowerOwner, flowerRecord, picture, "买回来")
        val edit = store.editRecord(flowerRecord)
        store.removeItem(edit, picture)
        store.completeMixedDraft(edit)
        val missing = store.readObservations(flowerOwner).single()
        assertEquals("买回来", missing.note)
        assertNull(missing.item)
        store.moveToTrash(flowerRecord)
        store.deletePermanently(flowerRecord)
        assertTrue(store.readObservations(flowerOwner).isEmpty())
    }
}
