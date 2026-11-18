package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class MediaFilterNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun removingMediaConditionPreservesSearchText() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val keyword = "类型查找" + System.nanoTime()
        val id = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit(keyword, 0))!!) }
        try {
            compose.onNodeWithTag("nav-Records").performClick()
            compose.onNodeWithText("搜索全部记录").performClick()
            compose.onNodeWithTag("search").performTextInput(keyword)
            waitCount(1)
            compose.onNodeWithText("内容类型").performScrollTo().performClick()
            compose.onNodeWithText("有照片").performClick()
            compose.onNodeWithText("完成选择").performClick()
            waitCount(0)
            compose.onNodeWithText("有照片 ×").performScrollTo().performClick()
            waitCount(1)
            compose.onNodeWithTag("search").assertTextContains(keyword)
        } finally {
            runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
        }
    }
    private fun waitCount(count: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("$count 条记录").fetchSemanticsNodes().isNotEmpty() }
    }
}
