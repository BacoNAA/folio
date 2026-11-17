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
class PlaceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)

    @Test fun createPlacePublishFindEditSharedInfoAndCreateAnotherRecord() {
        val name = "河边店${System.nanoTime()}"
        val text = "地点正文${System.nanoTime()}"
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").performTextInput(text)
        waitText("草稿已保存")
        editorClick("editor-clues")
        editorClick("edit-place")
        compose.onNodeWithText("新建地点").performClick()
        compose.onNodeWithTag("place-name").performTextInput(name)
        compose.onNodeWithTag("place-city").performTextInput("成都")
        compose.onNodeWithTag("place-address").performScrollTo().performTextInput("西街")
        compose.onNodeWithTag("place-note").performScrollTo().performTextInput("靠河")
        compose.onNodeWithText("保存地点").performClick()
        waitText("当前选择：$name（本草稿新地点）")
        assertTrue(runBlocking { store.listPlaces(name) }.isEmpty())
        compose.onNodeWithText("关闭地点").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        val first = runBlocking { store.queryRecords(text).single() }
        val place = first.place!!
        val times = runBlocking { store.openRecordContent(first.id) }!!.times
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("按地点").performClick()
        compose.onNodeWithTag("place-search").performTextInput("靠河")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-open-${place.id.value}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("place-open-${place.id.value}").performClick()
        waitText("地点操作")
        compose.onNodeWithText("地点操作").performClick()
        compose.onNodeWithText("编辑地点资料").performClick()
        compose.onNodeWithTag("place-name").performTextReplacement("新$name")
        compose.onNodeWithText("保存地点").performClick()
        waitText("地点操作")
        assertEquals(times, runBlocking { store.openRecordContent(first.id) }!!.times)
        assertEquals("新$name", runBlocking { store.openRecordContent(first.id) }!!.place!!.info.name)
        compose.onNodeWithText("地点操作").performClick()
        compose.onNodeWithText("在这里记一条").performClick()
        compose.onNodeWithTag("editor").performTextInput("$text，第二次")
        waitText("草稿已保存")
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        val records = runBlocking { store.queryRecords(placeId = place.id) }
        assertEquals(2, records.size)
        runBlocking { records.forEach { store.moveToTrash(it.id); store.deletePermanently(it.id) } }
    }

    @Test fun linkExistingRecordCommitsOnlyOnCompleteAndClearingPlaceKeepsKeyword() {
        val text = "关联地点${System.nanoTime()}"
        val seed = runBlocking { store.saveDraft(null, TextEdit("$text，已关联", 0))!! }
        val placeId = runBlocking { store.createDraftPlace(seed, PlaceInfo("小册${System.nanoTime()}")) }
        val first = runBlocking { store.completeMixedDraft(seed) }
        val second = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit("$text，待关联", 0))!!) }
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("按地点").performClick()
        compose.onNodeWithTag("place-search").performTextInput(runBlocking { store.openPlace(placeId) }!!.info.name)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-open-${placeId.value}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("place-open-${placeId.value}").performClick()
        waitText("地点操作")
        compose.onNodeWithText("地点操作").performClick()
        compose.onNodeWithText("关联已有记录").performClick()
        compose.onNodeWithTag("search").performTextInput("$text，待关联")
        waitText("1 条记录")
        compose.onNode(hasText("$text，待关联") and !hasSetTextAction()).performClick()
        waitText("草稿已保存")
        assertNull(runBlocking { store.openRecordContent(second) }!!.place)
        compose.onNodeWithText("完成").performClick()
        waitText("编辑")
        assertEquals(placeId, runBlocking { store.openRecordContent(second) }!!.place!!.id)
        compose.onNodeWithText("返回").performClick()
        waitText("地点操作")
        compose.onNodeWithTag("search").performTextInput(text)
        waitText("2 条记录")
        compose.onNodeWithText("移除地点条件 · 搜索全部记录").performClick()
        compose.onNodeWithTag("search").assertTextContains(text)
        waitText("2 条记录")
        runBlocking { listOf(first, second).forEach { store.moveToTrash(it); store.deletePermanently(it) } }
    }

    private fun editorClick(tag: String) {
        compose.onNodeWithTag("mixed-editor").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
