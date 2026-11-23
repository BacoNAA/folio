package com.baconaa.folio

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
class RecordGroupNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)
    private val suffix = System.nanoTime().toString()
    private val records = mutableListOf<RecordId>()

    private fun record(text: String) = runBlocking {
        val draft = requireNotNull(store.saveDraft(null, TextEdit(text + suffix, 0)))
        store.completeMixedDraft(draft).also { records.add(it) }
    }

    private fun group(name: String, members: List<RecordId>) = runBlocking {
        val id = RecordGroupId(name + suffix)
        assertEquals(GroupUpdate.Saved(id, members), store.createGroup(id, name + suffix, members))
        id
    }

    @After fun cleanUp() = runBlocking {
        store.listGroups().filter { it.name.endsWith(suffix) }.forEach { store.deleteGroup(it.id) }
        records.forEach { store.moveToTrash(it); store.deletePermanently(it) }
    }

    private fun order(id: RecordGroupId) = runBlocking { store.openGroup(id)!!.members.map { it.id } }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(matcher.description, 10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        waitFor(hasText(text) and hasClickAction() and isEnabled())
        compose.onNode(hasText(text) and hasClickAction() and isEnabled()).performClick()
    }

    private fun listClick(list: String, matcher: SemanticsMatcher) {
        waitFor(hasTestTag(list))
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        compose.onNode(matcher and hasAnyAncestor(hasTestTag(list))).performClick()
    }

    private fun pick(id: RecordId) = listClick("group-pick-list", hasTestTag("group-pick-${id.value}"))
    private fun memberAction(action: String, id: RecordId) = listClick("group-members", hasTestTag("group-$action-${id.value}"))
    private fun openGroup(id: RecordGroupId) = listClick("group-list", hasTestTag("open-group-${id.value}"))
    private fun openMember(text: String) = listClick("group-members", hasText(text + suffix) and hasClickAction())
    private fun enterRecords() = compose.onNodeWithTag("nav-Records").performClick()
    private fun back() = click("返回")

    @Test fun createInSelectionOrderJoinAnotherGroupAndReadTheSameEditedRecord() {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val family = group("家庭收藏", listOf(use))
        enterRecords()
        click("多选成组")
        listOf(buy, use, repair).forEach(::pick)
        click("选择记录组")
        waitFor(hasTestTag("group-name"))
        compose.onNodeWithTag("group-name").performTextInput("购机资料$suffix")
        val enteredName = hasTestTag("group-name") and hasText("购机资料$suffix")
        waitFor(enteredName)
        compose.activityRule.scenario.recreate()
        waitFor(enteredName)
        click("新建并收录")
        waitFor(hasTestTag("group-members"))
        val machine = runBlocking { store.listGroups().single { it.name == "购机资料$suffix" }.id }
        assertEquals(listOf(buy, use, repair), order(machine))
        openMember("试用")
        click("编辑")
        waitFor(hasTestTag("editor"))
        compose.onNodeWithTag("editor").performTextReplacement("试用后补记$suffix")
        click("完成")
        waitFor(hasText("编辑") and hasClickAction())
        back()
        waitFor(hasText("试用后补记$suffix"))
        memberAction("remove", repair)
        waitFor(hasText("已移出记录组。"))
        back()
        click("多选记录")
        pick(use)
        pick(buy)
        click("选择记录组")
        listClick("group-destination", hasTestTag("join-group-${family.value}"))
        waitFor(hasTestTag("group-members"))
        assertEquals(listOf(use, buy), order(family))
        openMember("试用后补记")
        waitFor(hasText("编辑") and hasClickAction())
        compose.onNodeWithText("试用后补记$suffix").assertIsDisplayed()
        back()
        memberAction("up", buy)
        waitFor(hasTestTag("group-up-${buy.value}") and isNotEnabled())
        assertEquals(listOf(buy, use), order(family))
        memberAction("remove", buy)
        memberAction("remove", use)
        waitFor(hasText("组内还没有记录。"))
        click("收录记录")
        pick(repair)
        click("确认收录")
        waitFor(hasText("维修$suffix"))
        assertEquals(listOf(repair), order(family))
        back()
        back()
        click("搜索全部记录")
        compose.onNodeWithTag("search").performTextInput("维修$suffix")
        waitFor(hasText("1 条记录"))
        listClick("search-results", hasText("维修$suffix") and hasClickAction())
        waitFor(hasText("编辑") and hasClickAction())
    }

    @Test fun invalidSelectionAndChangedOrderCanBeCorrectedOnTheSameScreen() {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val machine = group("购机资料", listOf(repair))
        enterRecords()
        click("多选成组")
        pick(buy)
        pick(use)
        click("选择记录组")
        waitFor(hasTestTag("group-name"))
        runBlocking { store.moveToTrash(use) }
        listClick("group-destination", hasTestTag("join-group-${machine.value}"))
        waitFor(hasText("1 条所选记录已失效，请确认剩余选择。"))
        waitFor(hasText("已选 1 条"))
        compose.onNodeWithTag("group-pick-${buy.value}").assertIsOn()
        assertEquals(listOf(repair), order(machine))
        click("选择记录组")
        listClick("group-destination", hasTestTag("join-group-${machine.value}"))
        waitFor(hasTestTag("group-up-${buy.value}") and isEnabled())
        val old = listOf(repair, buy)
        runBlocking { store.reorderGroup(machine, old, old.reversed()) }
        memberAction("up", buy)
        waitFor(hasText("成员顺序已变化，请按最新顺序再调整。"))
        memberAction("up", repair)
        waitFor(hasTestTag("group-up-${repair.value}") and isNotEnabled())
        assertEquals(old, order(machine))
        runBlocking { store.deleteGroup(machine) }
        memberAction("up", buy)
        click("返回组列表")
        waitFor(hasTestTag("group-list"))
        compose.onNodeWithTag("open-group-${machine.value}").assertDoesNotExist()
    }

    @Test fun returningFromMemberDetailKeepsTheScrolledPosition() {
        val ordered = (1..14).map { record("凭证$it-") }
        val receipts = group("凭证", ordered)
        enterRecords()
        click("记录组")
        openGroup(receipts)
        val last = "凭证14-$suffix"
        listClick("group-members", hasText(last) and hasClickAction())
        waitFor(hasText("编辑") and hasClickAction())
        compose.activityRule.scenario.recreate()
        back()
        waitFor(hasTestTag("group-members"))
        compose.onNode(hasText(last) and hasClickAction()).assertIsDisplayed()
    }

    @Test fun retryAfterUndoFromAnotherGroupReturnsToTheRestoredMembersPosition() {
        val ordered = (1..14).map { record("收据$it-") }
        val receipts = group("凭证", ordered)
        val family = group("家庭收藏", listOf(ordered[1]))
        enterRecords()
        click("记录组")
        openGroup(receipts)
        openMember("收据14-")
        waitFor(hasText("编辑") and hasClickAction())
        compose.onNodeWithText("收据14-$suffix").assertExists()
        click("删除")
        click("确认删除")
        waitFor(hasText("撤销删除"))
        assertEquals(ordered.dropLast(1), order(receipts))
        back()
        openGroup(family)
        waitFor(hasTestTag("group-members"))
        // 模拟原组内一条无日期的测试记录暂时读不出来。
        val db = Room.databaseBuilder(compose.activity.applicationContext, FolioDatabase::class.java, "folio.db").build()
        try {
            val updateZone = "UPDATE text_records SET eventZone = ? WHERE id = ?"
            val sql = db.openHelper.writableDatabase
            sql.execSQL(updateZone, arrayOf("Invalid/Zone", ordered.first().value))
            try {
                click("撤销删除")
                waitFor(hasText("读取失败，请重试。"))
                compose.onNodeWithText("组内还没有记录。").assertDoesNotExist()
            } finally {
                sql.execSQL(updateZone, arrayOf(null, ordered.first().value))
            }
            click("重试")
            waitFor(hasTestTag("group-members"))
            assertEquals(ordered, order(receipts))
            compose.onNodeWithText("凭证$suffix").assertExists()
            val restored = compose.onNode(hasText("收据14-$suffix") and hasClickAction())
            compose.waitUntil("重试后恢复组内阅读位置", 5_000) { restored.isDisplayed() }
            restored.performClick()
            waitFor(hasText("编辑") and hasClickAction())
        } finally {
            db.close()
        }
    }

    @Test fun memberDeletionUndoAndTrashRecoveryReachAllThreeSurvivingGroups() {
        val buy = record("购买")
        val use = record("试用")
        val repair = record("维修")
        val warranty = record("保修单")
        val original = listOf(buy, use, repair)
        val machine = group("购机资料", original)
        val originals = group("原件", original)
        val service = group("售后", original)
        val family = group("家庭收藏", listOf(use))
        val pending = "未完成修改$suffix"
        runBlocking { store.saveDraft(store.editRecord(use), TextEdit(pending, 0)) }
        enterRecords()
        click("记录组")
        openGroup(machine)
        openMember("试用")
        click("删除")
        click("确认删除")
        click("撤销删除")
        waitFor(hasText("已撤销删除"))
        assertEquals(original, order(machine))
        openMember("试用")
        click("继续未完成修改")
        waitFor(hasTestTag("editor"))
        compose.onNodeWithTag("editor").assertTextContains(pending)
        back()
        waitFor(hasText("试用$suffix"))
        click("删除")
        click("确认删除")
        waitFor(hasText("撤销删除"))
        memberAction("up", repair)
        back()
        openGroup(family)
        click("删除组")
        click("确认删除组")
        openGroup(originals)
        memberAction("remove", buy)
        back()
        openGroup(service)
        memberAction("remove", buy)
        memberAction("remove", repair)
        click("收录记录")
        pick(warranty)
        click("确认收录")
        waitFor(hasText("保修单$suffix"))
        back()
        back()
        click("回收站")
        listClick("trash-list", hasText("试用$suffix") and hasClickAction())
        click("恢复记录")
        waitFor(hasText("已恢复"))
        assertNull(runBlocking { store.openGroup(family) })
        assertEquals(listOf(repair, buy, use), order(machine))
        assertEquals(listOf(use, repair), order(originals))
        assertEquals(listOf(warranty, use), order(service))
        back()
        click("记录组")
        openGroup(originals)
        openMember("试用")
        click("继续未完成修改")
        waitFor(hasTestTag("editor"))
        compose.onNodeWithTag("editor").assertTextContains(pending)
    }
}
