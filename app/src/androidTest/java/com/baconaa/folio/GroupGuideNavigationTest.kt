package com.baconaa.folio

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupGuideNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)
    private val suffix = System.nanoTime().toString()
    private val group = RecordGroupId("guide-$suffix")
    private val records = mutableListOf<RecordId>()

    private fun record(text: String) = runBlocking {
        store.completeMixedDraft(requireNotNull(store.saveDraft(null, TextEdit(text + suffix, 0)))).also { records += it }
    }

    @After fun cleanUp() = runBlocking {
        store.deleteGroup(group)
        records.forEach { store.moveToTrash(it); store.deletePermanently(it) }
    }

    private fun layout() = runBlocking { store.openGuide(group)!!.layout }
    private fun action(text: String) = hasText(text) and hasClickAction() and isEnabled()

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(matcher.description, 10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        waitFor(action(text))
        compose.onNode(action(text)).performClick()
    }

    private fun inList(tag: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val list = hasTestTag(tag)
        waitFor(list)
        compose.onNode(list).performScrollToNode(matcher)
        return compose.onNode(matcher and hasAnyAncestor(list)).performScrollTo()
    }

    private fun enterGuide() {
        compose.onNodeWithTag("nav-Records").performClick()
        click("记录组")
        inList("group-list", hasTestTag("open-group-${group.value}")).performClick()
        inList("group-members", action("专题导览")).performClick()
        waitFor(hasTestTag("guide-reading"))
    }

    private fun edit() = inList("guide-reading", action("编辑导览")).performClick()

    private fun field(tag: String, value: String) {
        inList("guide-editor", hasTestTag(tag)).performTextReplacement(value)
        waitFor(hasTestTag(tag) and hasText(value))
    }

    private fun addSection(index: Int, title: String): String {
        inList("guide-editor", action("添加分段")).performClick()
        val node = inList("guide-editor", hasText("第 ${index} 段标题") and hasSetTextAction())
        val tag = node.fetchSemanticsNode().config[SemanticsProperties.TestTag]
        field(tag, title)
        return tag.removePrefix("guide-title-")
    }

    private fun assign(record: RecordId, title: String) {
        inList("guide-editor", hasTestTag("guide-assign-${record.value}")).performClick()
        val option = action(title) and hasAnyAncestor(hasTestTag("guide-section-menu"))
        waitFor(option)
        compose.onNode(option).performClick()
        waitFor(hasTestTag("guide-assign-${record.value}") and hasText("$title ▾"))
        compose.waitUntil("分段选择菜单关闭", 5_000) {
            compose.onAllNodesWithTag("guide-section-menu").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun sectionAction(id: String, label: String) =
        inList("guide-editor", action(label) and hasAnyAncestor(hasTestTag("guide-edit-section-$id"))).performClick()

    @Test fun createReorderReadAndRemoveASectionFromTheSameGroup() {
        val receipt = record("购买凭证")
        val repair = record("维修单")
        runBlocking { store.createGroup(group, "购机资料", listOf(receipt, repair)) }
        enterGuide()
        edit()
        field("guide-description", "咖啡机资料")
        addSection(1, "购买")
        val service = addSection(2, "送修")
        field("guide-note-$service", "换过一次泵")
        assign(receipt, "购买")
        assign(repair, "送修")
        sectionAction(service, "上移分段")
        click("保存导览")
        waitFor(hasTestTag("guide-reading"))
        assertEquals(listOf("送修", "购买"), layout().sections.map { it.title })
        click("返回")
        inList("group-members", action("专题导览")).performClick()
        inList("guide-reading", hasText("换过一次泵")).assertIsDisplayed()
        inList("guide-reading", action("维修单$suffix")).performClick()
        waitFor(action("编辑"))
        click("返回")
        waitFor(hasTestTag("guide-reading"))
        compose.onNodeWithText("送修").assertIsDisplayed()
        edit()
        sectionAction(service, "删除分段")
        waitFor(hasText("组内 1 条可见记录将移至未分段区，保存导览后生效。"))
        click("确认删除分段")
        click("保存导览")
        waitFor(hasTestTag("guide-reading"))
        assertNull(layout().members.single { it.recordId == repair }.sectionId)
        inList("guide-reading", hasText("未分段")).assertIsDisplayed()
        inList("guide-reading", action("维修单$suffix")).performClick()
        waitFor(action("编辑"))
    }

    @Test fun aStaleGuideKeepsTheTypedNoteAndShowsWhichMemberLeftBeforeSavingAgain() {
        val receipt = record("购买凭证")
        val repair = record("维修单")
        val spare = record("备用凭证")
        runBlocking { store.createGroup(group, "购机资料", listOf(receipt, repair, spare)) }
        enterGuide()
        edit()
        field("guide-description", "正在整理")
        addSection(1, "购买")
        assign(receipt, "购买")
        runBlocking {
            store.removeFromGroup(group, repair)
            store.reorderGroup(group, listOf(receipt, spare), listOf(spare, receipt))
        }
        click("保存导览")
        waitFor(hasText("「维修单$suffix」已移出。已按最新成员顺序更新，请核对后保存。"))
        inList("guide-editor", hasTestTag("guide-description")).assertTextContains("正在整理")
        assertEquals("", layout().description)
        click("保存导览")
        waitFor(hasTestTag("guide-reading"))
        val saved = layout()
        assertEquals("正在整理", saved.description)
        assertEquals(listOf(spare, receipt), saved.members.map { it.recordId })
        assertEquals(saved.sections.single().id, saved.members.last().sectionId)
    }

    @Test fun failedWriteRetainsInputAndConcurrentNotesRequireExplicitReplacement() {
        val receipt = record("购买凭证")
        runBlocking { store.createGroup(group, "资料", listOf(receipt)) }
        enterGuide()
        edit()
        field("guide-description", "本次输入")
        val db = Room.databaseBuilder(compose.activity.applicationContext, FolioDatabase::class.java, "folio.db").build()
        try {
            val sql = db.openHelper.writableDatabase
            val trigger = "guide_write_$suffix"
            sql.execSQL("""CREATE TRIGGER $trigger BEFORE UPDATE OF guideDescription ON record_groups
                WHEN OLD.id = '${group.value}' BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
            try {
                click("保存导览")
                waitFor(hasText("导览保存失败，当前输入已保留。"))
                inList("guide-editor", hasTestTag("guide-description")).assertTextContains("本次输入")
                assertEquals("", layout().description)
            } finally { sql.execSQL("DROP TRIGGER $trigger") }
            click("重试")
            waitFor(hasTestTag("guide-reading"))
            assertEquals("本次输入", layout().description)
        } finally { db.close() }
        edit()
        field("guide-description", "我仍在编辑")
        val old = layout()
        runBlocking { store.saveGuide(group, old, old.copy(description = "另一处的说明")) }
        click("保存导览")
        waitFor(hasText("导览已在别处修改，当前输入已保留。"))
        click("载入最新导览")
        click("保留当前输入")
        inList("guide-editor", hasTestTag("guide-description")).assertTextContains("我仍在编辑")
        click("载入最新导览")
        click("确认载入")
        inList("guide-editor", hasTestTag("guide-description")).assertTextContains("另一处的说明")
    }

    @Test fun movingWithinOneSectionKeepsOtherSlotsAndDiscardLeavesTheSavedNote() {
        val receipt = record("发票")
        val spare = record("备用凭证")
        val repair = record("维修单")
        runBlocking { store.createGroup(group, "购机资料", listOf(receipt, spare, repair)) }
        val old = layout()
        val section = GuideSection(GuideSectionId("papers"), "单据")
        val arranged = old.copy(sections = listOf(section),
            members = listOf(GuideMember(receipt, section.id), GuideMember(spare), GuideMember(repair, section.id)))
        runBlocking { store.saveGuide(group, old, arranged) }
        enterGuide()
        edit()
        inList("guide-editor", action("段内上移") and hasAnyAncestor(hasTestTag("guide-edit-member-${repair.value}"))).performClick()
        click("保存导览")
        waitFor(hasTestTag("guide-reading"))
        assertEquals(listOf(repair, spare, receipt), runBlocking { store.openGroup(group)!!.members.map { it.id } })
        edit()
        field("guide-description", "暂时的说明")
        click("返回")
        click("继续编辑")
        inList("guide-editor", hasTestTag("guide-description")).assertTextContains("暂时的说明")
        click("返回")
        click("放弃修改")
        waitFor(hasTestTag("guide-reading"))
        assertEquals("", layout().description)
    }

    @Test fun returningFromADeepMemberAfterActivityRecreationKeepsItsSectionVisible() {
        val receipts = (1..22).map { record("凭证$it-") }
        runBlocking { store.createGroup(group, "购机资料", receipts) }
        val old = layout()
        val buy = GuideSection(GuideSectionId("buy"), "购买")
        val service = GuideSection(GuideSectionId("service"), "送修")
        val arranged = old.copy(sections = listOf(buy, service), members = receipts.map {
            GuideMember(it, if (it == receipts.last()) service.id else buy.id)
        })
        runBlocking { store.saveGuide(group, old, arranged) }
        enterGuide()
        inList("guide-reading", action("凭证22-$suffix")).performClick()
        waitFor(action("编辑"))
        compose.activityRule.scenario.recreate()
        click("返回")
        waitFor(hasTestTag("guide-reading"))
        compose.onNodeWithText("送修").assertIsDisplayed()
        compose.onNode(action("凭证22-$suffix")).assertIsDisplayed()
    }
}
