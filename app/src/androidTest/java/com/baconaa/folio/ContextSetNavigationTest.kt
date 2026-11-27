package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ContextSetNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun applyingInEditorLeavesPublishedValuesAloneUntilCompletion() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val keyword = "套用样本" + System.nanoTime()
        val set = runBlocking {
            val draft = store.saveDraft(null, TextEdit("定义组合", 0))!!
            val tag = store.createDraftTag(draft, keyword)
            store.completeMixedDraft(draft)
            store.contextSets.save(null, keyword, listOf(tag.value), emptyList(), null)
        }
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").performTextInput("套用后的新记录")
        compose.waitUntil(15000) { compose.onAllNodesWithText("草稿已保存").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("mixed-editor")
        list.performScrollToNode(hasTestTag("editor-clues"))
        compose.onNodeWithTag("editor-clues").performClick()
        list.performScrollToNode(hasText("情境组合"))
        compose.onNodeWithText("情境组合").performClick()
        val apply = "context-apply-$set"
        compose.waitUntil(15000) { compose.onAllNodesWithTag(apply).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(apply).performScrollTo().performClick()
        compose.waitUntil(15000) { runBlocking {
            store.listDrafts().any { draft -> store.openDraftTags(draft.id)?.any { it.name == keyword } == true }
        } }
        runBlocking { check(store.contextSets.matches(set).size == 1) }
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("已保存 · 查看").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { check(store.contextSets.matches(set).size == 2) }
    }

    @Test fun createAndOpenMatchFromRecords() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val suffix = System.nanoTime()
        val rain = "雨天$suffix"
        val coffee = "咖啡$suffix"
        val body = "周末小记$suffix"
        val name = "窗边咖啡$suffix"
        runBlocking {
            val d = store.saveDraft(null, TextEdit(body, 0))!!
            store.createDraftTag(d, rain)
            store.createDraftTag(d, coffee)
            store.completeMixedDraft(d)
        }
        fun node(label: String, inDialog: Boolean = true): SemanticsNodeInteraction {
            val matcher = if (inDialog) hasText(label) and hasAnyAncestor(isDialog()) else hasText(label)
            compose.waitUntil(15000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
            return compose.onNode(matcher)
        }
        compose.onNodeWithTag("nav-Records").performClick()
        node("情境组合", inDialog = false).performClick()
        node("新建组合").performClick()
        node("组合名称").performTextInput(name)
        node(rain).performScrollTo().performClick()
        node(coffee).performScrollTo().performClick()
        node("保存组合").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("保存组合").fetchSemanticsNodes().isEmpty() }
        node(name).assertExists()
        val set = runBlocking { store.contextSets.list().single { it.row.name == name } }
        compose.onNodeWithTag("context-matches-${set.row.id}").performScrollTo().performClick()
        node(body).performClick()
        node("编辑", inDialog = false).assertExists()
    }
}
