package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class EventDateNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchesFromDatedToUndatedKeepingOtherConditions() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val keyword = "日期查找" + System.nanoTime()
        val ids = mutableListOf<RecordId>()
        try {
            runBlocking {
                for (date in listOf("2024-02-29", null, "2025-02-28")) {
                    val draft = store.saveDraft(null, TextEdit(keyword, 0))!!
                    store.saveDraftEventTime(draft, date?.let { EventTime(LocalDate.parse(it)) })
                    ids += store.completeMixedDraft(draft)
                }
            }
            compose.onNodeWithTag("nav-Records").performClick()
            compose.onNodeWithText("搜索全部记录").performClick()
            compose.onNodeWithTag("search").performTextInput(keyword)
            waitCount(3)
            compose.onNodeWithText("事件时间").performScrollTo().performClick()
            compose.onNodeWithTag("time-filter-from").performTextInput("2024-02")
            compose.onNodeWithText("应用时间条件").performClick()
            waitCount(1)
            compose.onNodeWithText("2024-02-29", substring = true).assertExists()
            compose.onNodeWithText("查看时间未定记录（1 条）").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("时间未定 ×").fetchSemanticsNodes().isNotEmpty() }
            waitCount(1)
            compose.onNodeWithText("时间未定 ×").performScrollTo().performClick()
            waitCount(3)
            compose.onNodeWithTag("search").assertTextContains(keyword)
        } finally {
            runBlocking { for (id in ids) { store.moveToTrash(id); store.deletePermanently(id) } }
        }
    }

    private fun waitCount(count: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("$count 条记录").fetchSemanticsNodes().isNotEmpty() }
    }
}
