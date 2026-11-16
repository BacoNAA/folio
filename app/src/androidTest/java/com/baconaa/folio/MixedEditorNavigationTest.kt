package com.baconaa.folio

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MixedEditorNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)

    @Test
    fun checklistAndTextCanBeReorderedRemovedRestoredAndPublishedFromThePage() {
        val text = "混排验收${System.nanoTime()}"
        compose.onNodeWithTag("quick-checklist").performScrollTo().performClick()
        compose.onNodeWithText("事项 1").performTextInput(text)
        waitText("草稿已保存")
        compose.onNodeWithContentDescription("完成事项 1").performClick().assertIsOn()
        compose.onNodeWithText("＋ 添加事项").performClick()
        compose.onNodeWithText("事项 2").performTextInput("带上相机")
        compose.onNodeWithTag("add-content").performScrollTo().performClick()
        compose.onNodeWithText("文字").performClick()
        compose.onNodeWithTag("editor").performTextInput("沿河散步")
        waitText("草稿已保存")
        compose.onNodeWithTag("item-menu-1").performScrollTo().performClick()
        compose.onNodeWithText("上移一项").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("item-menu-0") and hasAnySibling(hasText("文字"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("item-menu-0").performClick()
        compose.onNodeWithText("移除这一项").performClick()
        waitText("撤销移除")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("撤销移除").performClick()
        waitText("沿河散步")
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        compose.onNodeWithText("已保存 · 查看").performScrollTo().performClick()
        waitText("沿河散步")
        val record = runBlocking { store.queryRecords(text).single() }
        val items = runBlocking { store.openRecordContent(record.id) }!!.items
        assertEquals("沿河散步", (items[0] as ContentItem.Text).text)
        val entries = (items[1] as ContentItem.Checklist).entries
        assertEquals(listOf(text, "带上相机"), entries.map { it.text })
        assertTrue(entries[0].checked)
        runBlocking { store.moveToTrash(record.id); store.deletePermanently(record.id) }
    }

    @Test
    fun microphoneEntryStopsAndSavesOnBackgroundAndCanBePlayedAndCompleted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(compose.activity.packageName, Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithTag("quick-audio").performScrollTo().performClick()
        waitText("正在录音")
        compose.onNodeWithText("录音尚未保存").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsNotEnabled()
        android.os.SystemClock.sleep(1_500)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText("草稿已保存")
        compose.onNodeWithText("正在录音").assertDoesNotExist()
        val caption = "录音页面验收${System.nanoTime()}"
        compose.onNodeWithText("录音说明（选填）").performTextInput(caption)
        compose.onNodeWithText("试听").performClick()
        waitText("暂停")
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithText("试听").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        val record = runBlocking { store.queryRecords(caption).single() }
        val audio = runBlocking { store.openRecordContent(record.id) }!!.items.single() as ContentItem.Audio
        assertTrue(audio.durationMillis > 0)
        assertEquals(caption, audio.caption)
        runBlocking { store.moveToTrash(record.id); store.deletePermanently(record.id) }
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
