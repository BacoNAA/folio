package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensQuickNoteAndSwitchesBetweenBothTabs() {
        compose.onNodeWithTag("nav-Capture").assertIsSelected()
        compose.onNodeWithTag("write").assertIsDisplayed()
        compose.onNodeWithTag("nav-Records").performClick().assertIsSelected()
        compose.onNodeWithText("搜索全部记录").assertIsDisplayed()
        compose.onNodeWithTag("nav-Capture").performClick().assertIsSelected()
        compose.onNodeWithTag("write").assertIsDisplayed()
    }

    @Test
    fun draftResumesAfterActivityRecreationAndOnlyCompletedTextIsSearchable() {
        val text = "青瓦桥初雪${System.nanoTime()}"
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").performTextInput(text)
        waitText("草稿已保存")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("editor").assertTextContains(text)
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextInput(text)
        waitText("0 条记录")
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("继续草稿", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续草稿", substring = true).performClick()
        // Other tests or manual use may already have drafts; select by content when a picker is needed.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("editor").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("editor").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText(text).performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").performTextInput("，河边见")
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        compose.onNodeWithText("已保存 · 查看").performClick()
        waitText("$text，河边见")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextReplacement(text)
        waitText("1 条记录")
        compose.onNodeWithText("$text，河边见").performClick()
        compose.onNodeWithText("编辑").performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").performTextReplacement("$text，未完成修改")
        compose.onNodeWithText("返回").performClick()
        waitText("另有未完成修改，当前显示上次完成的内容。")
        compose.onNodeWithText("$text，河边见").assertIsDisplayed()
        compose.onNodeWithText("继续未完成修改").performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").assertTextContains("$text，未完成修改")
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("确认放弃").performClick()
        waitText("$text，河边见")
        compose.onNodeWithText("编辑").performClick()
        waitText("草稿已保存")
        compose.onNodeWithTag("editor").performTextReplacement("$text，完成修改")
        compose.onNodeWithText("完成").performClick()
        waitText("$text，完成修改")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("search").assertTextContains(text)
        waitText("1 条记录")
    }

    @Test
    fun blankEditorCannotPublishAndCanBeAbandoned() {
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithText("完成").assertIsNotEnabled()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("确认放弃").performClick()
        compose.onNodeWithTag("write").assertIsDisplayed()
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
