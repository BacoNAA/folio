package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)

    @Test
    fun continuousKeyboardInputSurvivesFirstDraftCreation() {
        val text = "org-restart-1117-abcdefghijklmnopqrstuvwxyz"
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").assertIsFocused()
        // Compose 焦点就绪后，仍需等待系统键盘的窗口切换结束；有硬键盘时不要求弹出软键盘。
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        instrumentation.sendStringSync(text)
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").assertTextContains(text)
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("确认放弃").performClick()
    }

    @Test
    fun dateTagAndMarksCanBeSavedAndUsedToFindTheRecord() {
        val text = "标签页面${System.nanoTime()}"
        val tagName = "出行${System.nanoTime()}"
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").performTextInput(text)
        waitText("草稿已保存")
        compose.onNodeWithTag("editor-clues").performScrollTo().performClick()
        compose.onNodeWithTag("edit-time").performScrollTo().performClick()
        compose.onNodeWithTag("event-date").performTextReplacement("2021-02-30")
        compose.onNodeWithText("应用时间").performClick()
        waitText("请检查日期、时刻和时区是否有效。")
        compose.onNodeWithTag("event-date").performTextReplacement("2021-11-08")
        compose.onNodeWithTag("event-time").performTextReplacement("")
        compose.onNodeWithTag("event-zone").performTextReplacement("Asia/Tokyo")
        compose.onNodeWithText("应用时间").performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("edit-tags").performScrollTo().performClick()
        compose.onNodeWithTag("new-tag").performTextInput(tagName)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("新建并选中") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("新建并选中").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("改名").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭标签").performClick()
        waitText("标签：$tagName")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        compose.onNodeWithText("已保存 · 查看").performScrollTo().performClick()
        waitText("加星")
        compose.onNodeWithText("加星").performClick()
        waitText("已加星")
        compose.onNodeWithText("置顶").performClick()
        waitText("已置顶")
        compose.onNodeWithText("事件时间：2021-11-08 · Asia/Tokyo").assertIsDisplayed()
        val record = runBlocking { store.queryRecords(text).single() }
        val content = runBlocking { store.openRecordContent(record.id) }!!
        assertNull(content.times.eventTime!!.time)
        assertEquals(tagName, content.tags.single().name)
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("星标").performClick()
        waitText(text)
        compose.onNodeWithTag("search").performTextInput(text)
        waitText("1 条记录")
        compose.onNodeWithText("筛选标签").performClick()
        compose.onNodeWithTag("tag-choice-${content.tags.single().id.value}").performScrollTo().performClick()
        compose.onNodeWithText("关闭标签").performClick()
        waitText("1 条记录")
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithTag("search").assertTextContains(text)
        waitText("1 条记录")
        runBlocking { store.moveToTrash(record.id); store.deletePermanently(record.id) }
    }

    @Test
    fun trashIsReadOnlyAndRestoresBothVersionsBeforeConfirmedPermanentDeletion() {
        val text = "回收站页面${System.nanoTime()}"
        val id = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit(text, 0))!!) }
        val draft = runBlocking { store.editRecord(id) }
        runBlocking { store.saveDraft(draft, TextEdit("$text，未完成", 0)) }
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextInput(text)
        waitText("1 条记录")
        compose.onNode(hasText(text) and !hasSetTextAction()).performClick()
        waitText("删除")
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitText("撤销删除")
        waitText("0 条记录")
        compose.onNodeWithText("撤销删除").performClick()
        waitText("1 条记录")
        compose.onNodeWithTag("search").assertTextContains(text)
        compose.onNode(hasText(text) and !hasSetTextAction()).performClick()
        waitText("删除")
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitText("0 条记录")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("回收站").performClick()
        waitText(text)
        compose.onNode(hasText(text) and !hasSetTextAction()).performClick()
        waitText("恢复记录")
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("$text，未完成").assertDoesNotExist()
        compose.onNodeWithText("彻底删除").performClick()
        compose.onNodeWithText("取消").performClick()
        assertNotNull(runBlocking { store.openTrashRecord(id) })
        compose.onNodeWithText("恢复记录").performClick()
        waitText("查看恢复记录")
        compose.onNodeWithText("查看恢复记录").performClick()
        waitText("继续未完成修改")
        compose.onNodeWithText("继续未完成修改").performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").assertTextContains("$text，未完成")
        compose.onNodeWithText("返回").performClick()
        waitText("删除")
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitText(text)
        compose.onNode(hasText(text) and !hasSetTextAction()).performClick()
        waitText("彻底删除")
        compose.onNodeWithText("彻底删除").performClick()
        compose.onNodeWithText("确认彻底删除").performClick()
        waitText("已彻底删除")
        assertNull(runBlocking { store.openTrashRecord(id) })
        assertNull(runBlocking { store.openDraft(draft) })
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
