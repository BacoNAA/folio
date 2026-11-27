package com.baconaa.folio.records

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class FeelingScaleTest : PhotoStoreFixture() {
    private val five = listOf("不辣", "微辣", "中辣", "很辣", "特辣")

    @Test fun visitsKeepTheirOwnDegreesAndSearchUsesTheExactHistoricalChoice() = runBlocking {
        val scale = store.saveFeelingScale(null, "辣度", five)
        val other = store.saveFeelingScale(null, "辣度", five)
        val draft = store.saveDraft(null, TextEdit("同一家面馆第一碗", 0))!!
        val place = store.createDraftPlace(draft, PlaceInfo("面馆"))
        val three = scale.choice(3)
        store.setDraftFeeling(draft, three)
        val first = store.completeMixedDraft(draft)
        val secondDraft = store.saveDraft(null, TextEdit("同一家面馆第二碗", 0))!!
        store.selectDraftPlace(secondDraft, place)
        store.setDraftFeeling(secondDraft, scale.choice(4))
        val second = store.completeMixedDraft(secondDraft)
        store.savePlaceImpression(place, "很想再来")
        val custom = FeelingChoice(other.id, 0, 0, "香多于辣")
        store.setPlaceFeeling(place, custom)
        val updated = store.saveFeelingScale(scale, "辣度", five + listOf("麻辣", "七级", "八级", "九级", "十级"))
        val thirdDraft = store.saveDraft(null, TextEdit("另一家面馆", 0))!!
        assertTrue(runCatching { store.setDraftFeeling(thirdDraft, three) }.isFailure)
        store.setDraftFeeling(thirdDraft, updated.choice(6))
        store.setDraftFeeling(thirdDraft, other.choice(3))
        val third = store.completeMixedDraft(thirdDraft)
        val pending = store.editRecord(first)
        store.setDraftFeeling(pending, updated.choice(3))
        reopenDatabase()

        suspend fun results(choice: FeelingChoice, filterPlace: PlaceId? = null) =
            store.queryRecords(RecordQuery(placeId = filterPlace, feeling = choice)).certain.map { it.id }
        assertEquals(listOf(first), results(three, place))
        assertEquals(listOf(second), results(scale.choice(4), place))
        assertEquals(listOf(third), results(other.choice(3)))
        assertEquals(emptyList<RecordId>(), results(other.choice(3), place))
        assertEquals(emptyList<RecordId>(), results(updated.choice(3)))
        assertEquals(setOf(three, scale.choice(4), updated.choice(6)), store.historicalFeelings(scale.id).toSet())
        assertEquals(listOf(updated.choice(3)), store.draftFeelings(pending))
        assertEquals(three, store.openRecordContent(first)!!.feelings.single().choice)
        store.discardDraft(pending)
        store.savePlaceImpression(place, "下次再换汤底")
        assertEquals(listOf(custom), store.placeFeelings(place))
        assertEquals("下次再换汤底", store.placeImpression(place))
        assertEquals(three, store.openRecordContent(first)!!.feelings.single().choice)
        store.moveToTrash(first)
        assertTrue(results(three).isEmpty())
        store.restoreRecord(first)
        assertEquals(listOf(first), results(three))
        val edit = store.editRecord(first)
        store.setDraftFeeling(edit, custom)
        store.removeDraftFeeling(edit, scale.id)
        assertEquals(first, store.completeMixedDraft(edit))
        assertEquals(listOf(first), results(custom))
        assertTrue(results(three).isEmpty())
    }

    @Test fun templatesReuseScaleIdentityAndAnUnfilledSuggestionIsOptional() = runBlocking {
        val scale = store.saveFeelingScale(null, "辣度", five)
        val field = TemplateField(TemplateFieldType.TEXT)
        val template = store.saveTemplate(null, "试吃", listOf(field), emptySet(), setOf(scale.id))
        val a = store.startTemplateDraft(template.id, template.revision)
        val b = store.startTemplateDraft(template.id, template.revision)
        store.saveTemplate(template, "改名", listOf(field), emptySet(), emptySet())
        assertEquals(setOf(scale.id), store.draftFeelingState(a).suggested)
        assertEquals(setOf(scale.id), store.draftFeelingState(b).suggested)
        store.setDraftFeeling(a, scale.choice(3))
        store.removeDraftFeeling(a, scale.id)
        assertEquals(DraftFeelingState(emptyList(), setOf(scale.id)), store.draftFeelingState(a))
        val first = store.completeMixedDraft(a, store.openMixedDraft(a)!!.items.first().id, TextEdit("清淡的一碗", 0))
        assertEquals(first, store.completeMixedDraft(a))
        assertEquals(setOf(scale.id), store.openRecordContent(first)!!.templateScaleIds)
        assertTrue(store.openRecordContent(first)!!.feelings.isEmpty())
        val free = FeelingChoice(scale.id, 0, 0, "麻香")
        store.setDraftFeeling(b, free)
        val second = store.completeMixedDraft(b, store.openMixedDraft(b)!!.items.first().id, TextEdit("麻香的一碗", 0))
        assertEquals(listOf(second), store.queryRecords(RecordQuery(feeling = free)).certain.map { it.id })
        val title = store.saveDraftTitle(null, "先记感受")!!
        store.setDraftFeeling(title, free)
        assertEquals(title, store.saveDraftTitle(title, ""))
        assertEquals(listOf(free), store.draftFeelings(title))
    }

    @Test fun backupPreflightAcceptsOldDenominatorAndRejectsInvalidDegreesAndPrivatePlaceOwners() = runBlocking {
        val scale = store.saveFeelingScale(null, "辣度", five)
        val draft = store.saveDraft(null, TextEdit("原来的一碗", 0))!!
        store.setDraftFeeling(draft, scale.choice(5))
        val record = store.completeMixedDraft(draft)
        store.saveFeelingScale(scale, "辣度", listOf("清淡", "麻辣"))
        validateBackupData(database)
        val sql = database.openHelper.writableDatabase
        fun change(set: String) = sql.execSQL("UPDATE record_feelings SET $set WHERE recordId=?", arrayOf(record.value))
        for (invalid in listOf("level=6", "level=4294967301", "label=''", "suggested=2", "total=-1")) {
            change(invalid)
            assertTrue(invalid, runCatching { validateBackupData(database) }.isFailure)
            change("level=5,total=5,label='特辣',suggested=0")
        }
        val privateDraft = store.saveDraft(null, TextEdit("草稿", 0))!!
        val place = store.createDraftPlace(privateDraft, PlaceInfo("私有面馆"))
        sql.execSQL("INSERT INTO place_impressions(placeId,note) VALUES (?,?)", arrayOf(place.value, "印象"))
        assertTrue(runCatching { validateBackupData(database) }.isFailure)
    }
}
