package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordGroupGuidesTest : PhotoStoreFixture() {
    private val group = RecordGroupId("machine")
    private val buying = GuideSection(GuideSectionId("buy"), "购买", "票据")
    private val servicing = GuideSection(GuideSectionId("service"), "送修", "维修经过")

    private suspend fun record(text: String) =
        store.completeMixedDraft(requireNotNull(store.saveDraft(null, TextEdit(text, text.length))))

    private suspend fun layout() = requireNotNull(store.openGuide(group)).layout

    @Test fun guideReopensWithOneSetOfMembersAndAllowsDeletingASection() = runBlocking {
        val receipt = record("购买凭证")
        val repair = record("维修单")
        store.createGroup(group, "购机资料", listOf(receipt, repair))
        val initial = layout()
        val arranged = GuideLayout("咖啡机", listOf(buying, servicing),
            listOf(GuideMember(receipt, buying.id), GuideMember(repair, servicing.id)))
        assertEquals(GuideUpdate.Saved, store.saveGuide(group, initial, arranged))
        val reversed = arranged.copy(sections = arranged.sections.reversed())
        assertEquals(GuideUpdate.Saved, store.saveGuide(group, arranged, reversed))
        reopenDatabase()
        assertEquals(reversed, layout())
        assertEquals(GuideUpdate.Saved, store.saveGuide(group, arranged, reversed))
        val simplified = reversed.copy(sections = listOf(buying), members = listOf(GuideMember(repair), GuideMember(receipt, buying.id)))
        assertEquals(GuideUpdate.Saved, store.saveGuide(group, reversed, simplified))
        assertEquals(simplified, layout())
        assertEquals(listOf(repair, receipt), store.openGroup(group)!!.members.map { it.id })
        val draft = store.editRecord(repair)
        store.saveDraft(draft, TextEdit("维修单补记", 0))
        store.completeMixedDraft(draft)
        val card = store.openGuide(group)!!.content.members.first()
        assertEquals("维修单补记", (card.preview.items.single() as PreviewContentItem.Text).text)
    }

    @Test fun staleEditorsCannotOverwriteMovesNotesOrReinstateRemovedMembers() = runBlocking {
        val receipt = record("购买凭证")
        val repair = record("维修单")
        store.createGroup(group, "购机资料", listOf(receipt, repair))
        val old = layout()
        val pending = old.copy(description = "我的安排")
        store.reorderGroup(group, listOf(receipt, repair), listOf(repair, receipt))
        assertEquals(layout(), (store.saveGuide(group, old, pending) as GuideUpdate.Changed).current.layout)
        val reordered = layout()
        val notes = reordered.copy(description = "他人已保存", sections = listOf(servicing))
        store.saveGuide(group, reordered, notes)
        assertEquals(notes, (store.saveGuide(group, reordered, reordered.copy(description = "旧说明")) as GuideUpdate.Changed).current.layout)
        store.removeFromGroup(group, repair)
        val latest = layout()
        assertEquals(latest, (store.saveGuide(group, notes, pending) as GuideUpdate.Changed).current.layout)
        assertEquals(listOf(GuideMember(receipt)), latest.members)
        assertEquals(repair, store.search("维修单").single().id)
        assertTrue(runCatching { store.saveGuide(group, latest, latest.copy(members = listOf(GuideMember(repair)))) }.isFailure)
        assertTrue(runCatching { store.saveGuide(group, latest, latest.copy(members = listOf(GuideMember(receipt, buying.id)))) }.isFailure)
        assertEquals(latest, layout())
    }

    @Test fun hiddenAssignmentsFollowTheRecordOnlyWhileTheirSectionExists() = runBlocking {
        val receipt = record("购买凭证")
        store.createGroup(group, "购机资料", listOf(receipt))
        val old = layout()
        store.saveGuide(group, old, old.copy(sections = listOf(buying), members = listOf(GuideMember(receipt, buying.id))))
        store.moveToTrash(receipt)
        val empty = layout()
        assertTrue(empty.members.isEmpty())
        store.saveGuide(group, empty, empty.copy(sections = listOf(buying.copy(description = "新说明"))))
        store.restoreRecord(receipt)
        assertEquals(listOf(GuideMember(receipt, buying.id)), layout().members)
        store.moveToTrash(receipt)
        val hidden = layout()
        store.saveGuide(group, hidden, hidden.copy(sections = emptyList()))
        store.restoreRecord(receipt)
        assertEquals(listOf(GuideMember(receipt)), layout().members)
        store.saveGuide(group, layout(), layout().copy(sections = listOf(buying), description = "说明"))
        store.deleteGroup(group)
        assertNull(store.openGuide(group))
        assertEquals(GuideUpdate.MissingGroup, store.saveGuide(group, old, old))
        assertTrue(database.guideDao().sections(group.value).isEmpty())
        assertEquals("", database.groupDao().group(group.value)!!.guideDescription)
    }

    @Test fun failedAssignmentRollsBackDescriptionSectionOrderAndMembershipChanges() = runBlocking {
        val receipt = record("购买凭证")
        val repair = record("维修单")
        store.createGroup(group, "购机资料", listOf(receipt, repair))
        val old = layout()
        val edited = GuideLayout("待保存", listOf(servicing), listOf(GuideMember(repair, servicing.id), GuideMember(receipt)))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_assignment BEFORE INSERT ON group_guide_assignments
            BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
        try {
            assertTrue(runCatching { store.saveGuide(group, old, edited) }.isFailure)
            assertEquals(old, layout())
        } finally { sql.execSQL("DROP TRIGGER fail_assignment") }
        assertEquals(GuideUpdate.Saved, store.saveGuide(group, old, edited))
        assertEquals(edited, layout())
    }
}
