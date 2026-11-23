package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordGroupsTest : PhotoStoreFixture() {
    private val machine = RecordGroupId("machine")
    private val family = RecordGroupId("family")

    private suspend fun record(text: String): RecordId {
        val draft = requireNotNull(store.saveDraft(null, TextEdit(text, text.length)))
        return store.completeMixedDraft(draft)
    }

    private suspend fun members(id: RecordGroupId) = requireNotNull(store.openGroup(id)).members.map { it.id }

    @Test
    fun largeSelectionKeepsItsOrderAndPreviewsAcrossQueryBatches() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""WITH RECURSIVE sample(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM sample WHERE n < 1001)
            INSERT INTO text_records(id,sourceDraftId,body,firstRecordedAt,modifiedAt)
            SELECT 'r'||n,'d'||n,'凭证'||n,n,n FROM sample""")
        sql.execSQL("""INSERT INTO record_items(recordId,position,itemId,text) SELECT id,0,id,body FROM text_records""")
        val selected = (1001 downTo 1).map { RecordId("r$it") }
        assertEquals(GroupUpdate.Saved(machine, selected), store.createGroup(machine, "凭证", selected))
        val cards = store.openGroup(machine)!!.members
        assertEquals(selected, cards.map { it.id })
        assertEquals((1001 downTo 1).map { "凭证$it" }, cards.map { (it.preview.items.single() as PreviewContentItem.Text).text })
    }

    @Test
    fun groupReadsOnlyItsMembersAndReportsAnUnreadableMembersData() = runBlocking {
        val receipt = record("购机凭证")
        val draft = requireNotNull(store.saveDraft(null, TextEdit("维修", 2)))
        val place = store.createDraftPlace(draft, PlaceInfo("维修店"))
        val repair = store.completeMixedDraft(draft)
        store.createGroup(machine, "购机资料", listOf(receipt))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE places SET latitude = 91, longitude = 0 WHERE id = ?", arrayOf(place.value),
        )
        assertTrue(runCatching { store.openRecordContent(repair) }.isFailure)
        assertEquals(listOf(receipt), members(machine))
        store.appendToGroup(machine, listOf(repair))
        assertTrue(runCatching { store.openGroup(machine) }.isFailure)
    }

    @Test
    fun creationRetriesAndSeparateGroupEditsKeepOnePublishedVersion() = runBlocking {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val order = listOf(buy, use, repair)
        val initial = store.openRecordContent(use)
        val saved = GroupUpdate.Saved(machine, order)
        assertEquals(saved, store.createGroup(machine, " 购机资料 ", order))
        reopenDatabase()
        assertEquals(saved, store.createGroup(machine, "购机资料", order))
        assertEquals(listOf(RecordGroup(machine, "购机资料")), store.listGroups())
        assertEquals(order, members(machine))

        store.createGroup(family, "家庭收藏", listOf(use))
        assertEquals(saved, store.appendToGroup(machine, listOf(use, use)))
        assertEquals(GroupUpdate.Saved(family, listOf(use)), store.appendToGroup(family, listOf(use)))
        val moved = listOf(repair, buy, use)
        assertEquals(GroupUpdate.Saved(machine, moved), store.reorderGroup(machine, order, moved))
        assertTrue(store.removeFromGroup(machine, repair))
        assertFalse(store.removeFromGroup(machine, repair))
        assertEquals(repair, store.search("维修").single().id)
        reopenDatabase()
        assertEquals(listOf(buy, use), members(machine))
        assertEquals(listOf(use), members(family))
        assertEquals(initial, store.openRecordContent(use))

        val draft = store.editRecord(use)
        store.saveDraft(draft, TextEdit("试用后补记", 5))
        val previous = store.listRecentRecords().single { it.id == use }
        assertNull(previous.title)
        assertEquals("试用", (previous.preview.items.single() as PreviewContentItem.Text).text)
        for (id in listOf(machine, family)) assertEquals(previous, store.openGroup(id)!!.members.single { it.id == use })
        assertEquals(use, store.completeMixedDraft(draft))
        val updated = store.listRecentRecords().single { it.id == use }
        assertEquals("试用后补记", (updated.preview.items.single() as PreviewContentItem.Text).text)
        for (id in listOf(machine, family)) assertEquals(updated, store.openGroup(id)!!.members.single { it.id == use })

        assertTrue(store.removeFromGroup(family, use))
        assertEquals(emptyList<RecordId>(), members(family))
        // 同一次选择含重复成员时，按第一次出现的先后收录。
        assertEquals(GroupUpdate.Saved(family, listOf(repair, buy)), store.appendToGroup(family, listOf(repair, buy, repair)))
    }

    @Test
    fun staleSelectionAndConcurrentReordersLeaveTheWinningOrderIntact() = runBlocking {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val draft = requireNotNull(store.saveDraft(null, TextEdit("未完成", 3)))
        val draftAsRecord = RecordId(draft.value)
        assertTrue(store.moveToTrash(use))
        val unavailable = GroupUpdate.UnavailableRecords(listOf(use, draftAsRecord))
        assertEquals(unavailable, store.createGroup(machine, "购机资料", listOf(buy, use, draftAsRecord)))
        assertNull(store.openGroup(machine))
        assertTrue(runCatching { store.createGroup(machine, "  ", listOf(buy)) }.isFailure)
        assertTrue(runCatching { store.createGroup(machine, "购机资料", emptyList()) }.isFailure)
        store.createGroup(machine, "购机资料", listOf(buy))
        assertEquals(unavailable, store.appendToGroup(machine, listOf(repair, use, draftAsRecord, buy)))
        assertEquals(listOf(buy), members(machine))

        assertTrue(store.restoreRecord(use))
        val base = listOf(buy, repair, use)
        assertEquals(GroupUpdate.Saved(machine, base), store.appendToGroup(machine, listOf(repair, buy, use)))
        assertEquals(GroupUpdate.OrderChanged(base), store.reorderGroup(machine, listOf(buy), listOf(buy)))
        assertTrue(runCatching { store.reorderGroup(machine, base, listOf(buy, buy, use)) }.isFailure)
        val attempts = listOf(listOf(use, repair, buy), listOf(repair, use, buy)).map { target ->
            async { store.reorderGroup(machine, base, target) }
        }.awaitAll()
        val winner = attempts.filterIsInstance<GroupUpdate.Saved>().single()
        assertEquals(GroupUpdate.OrderChanged(winner.order), attempts.filterIsInstance<GroupUpdate.OrderChanged>().single())
        reopenDatabase()
        assertEquals(winner.order, members(machine))
    }

    @Test
    fun undoAndTrashRestoreUseEachSurvivingGroupsOwnNeighbors() = runBlocking {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val warranty = record("保修单")
        val original = listOf(buy, use, repair)
        val originals = RecordGroupId("originals")
        val service = RecordGroupId("service")
        for ((id, name) in listOf(machine to "购机资料", originals to "原件", service to "售后")) {
            store.createGroup(id, name, original)
        }
        store.createGroup(family, "家庭收藏", listOf(use))
        val draft = store.editRecord(use)
        val pending = TextEdit("未完成的试用修改", 3)
        store.saveDraft(draft, pending)
        assertTrue(store.moveToTrash(use))
        assertFalse(store.moveToTrash(use))
        assertEquals(listOf(buy, repair), members(machine))
        assertTrue(store.search("试用").isEmpty())
        assertNull(store.openDraft(draft))
        assertTrue(store.restoreRecord(use))
        assertEquals(original, members(machine))
        assertEquals("试用", store.openRecord(use))
        assertEquals(pending, store.openDraft(draft))

        assertTrue(store.moveToTrash(use))
        assertTrue(store.deleteGroup(family))
        assertFalse(store.deleteGroup(family))
        store.reorderGroup(machine, listOf(buy, repair), listOf(repair, buy))
        store.removeFromGroup(originals, buy)
        store.removeFromGroup(service, buy)
        store.removeFromGroup(service, repair)
        store.appendToGroup(service, listOf(warranty))
        reopenDatabase()
        assertTrue(store.restoreRecord(use))
        assertFalse(store.restoreRecord(use))
        assertEquals(listOf(repair, buy, use), members(machine))
        assertEquals(listOf(use, repair), members(originals))
        assertEquals(listOf(warranty, use), members(service))
        assertNull(store.openGroup(family))
        assertFalse(store.listGroups().any { it.id == family })
        assertEquals(GroupUpdate.MissingGroup, store.createGroup(family, "家庭收藏", listOf(use)))
        assertEquals(GroupUpdate.MissingGroup, store.appendToGroup(family, listOf(buy)))
        assertEquals("试用", store.openRecord(use))
        assertEquals(pending, store.openDraft(draft))
    }

    @Test
    fun failedRestoreRollsBackVisibilityAndPermanentDeletionClearsOnlyThatMembership() = runBlocking {
        val buy = record("购买")
        val use = record("试用")
        store.createGroup(machine, "购机资料", listOf(buy, use))
        assertTrue(store.moveToTrash(use))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_order BEFORE UPDATE OF position ON record_group_members
            WHEN NEW.position IS NOT NULL BEGIN SELECT RAISE(ABORT, 'reject_order'); END""")
        try {
            assertTrue(runCatching { store.restoreRecord(use) }.isFailure)
        } finally {
            sql.execSQL("DROP TRIGGER reject_order")
        }
        reopenDatabase()
        assertNotNull(store.openTrashRecord(use))
        assertNull(store.openRecord(use))
        assertEquals(listOf(buy), members(machine))
        assertTrue(store.restoreRecord(use))
        assertEquals(listOf(buy, use), members(machine))
        assertTrue(store.moveToTrash(use))
        assertTrue(store.deletePermanently(use).deleted)
        assertFalse(store.restoreRecord(use))
        assertEquals(listOf(buy), members(machine))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM record_group_members").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getInt(0))
        }
    }
}
