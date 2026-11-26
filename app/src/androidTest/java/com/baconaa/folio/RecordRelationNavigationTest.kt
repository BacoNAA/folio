package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordRelationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun linkFromDetailThenFollowItInBothDirections() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val suffix = System.nanoTime()
        val records = runBlocking { listOf("维修单", "新凭证").map {
            store.completeMixedDraft(store.saveDraft(null, TextEdit("$it$suffix", 0))!!)
        } }
        fun click(label: String) {
            val match = hasText(label) and hasClickAction()
            compose.waitUntil(label, 10_000) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNode(match)
            if (label == "关联记录") node.performScrollTo()
            node.performClick()
        }
        try {
            compose.onNodeWithTag("nav-Records").performClick()
            click("最近记录")
            val first = hasText("维修单$suffix") and hasClickAction()
            compose.waitUntil("首条记录出现", 10_000) { compose.onAllNodesWithTag("records-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("records-list").performScrollToNode(first)
            compose.onNode(first).performClick()
            click("关联记录")
            click("添加关联")
            val choose = hasTestTag("relate-${records.last().value}")
            compose.onNodeWithTag("relation-list").performScrollToNode(choose)
            compose.onNode(choose).performClick()
            click("打开原记录")
            click("关联记录")
            click("打开原记录")
            compose.waitUntil("已返回维修单", 10_000) {
                compose.onAllNodesWithText("维修单$suffix").fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { assertEquals(setOf(records.last()), store.relationChoices(records.first()).related) }
        } finally { runBlocking { records.forEach { store.moveToTrash(it); store.deletePermanently(it) } } }
    }
}
