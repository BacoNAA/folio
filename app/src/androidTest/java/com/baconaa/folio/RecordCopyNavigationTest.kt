package com.baconaa.folio

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioApp
import com.baconaa.folio.ui.FolioViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordCopyNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)

    @Test fun selectedChecklistAndPhotoResumeInSourceOrderAndPublishIndependently() {
        val title = "春游装备${System.nanoTime()}"
        val source = runBlocking {
            val photo = LocalPhotoOriginals.get(context).importPhoto(Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            val list = listOf(ChecklistEntry(ChecklistEntryId.create(), "帐篷", true), ChecklistEntry(ChecklistEntryId.create(), "水壶"))
            val draft = store.appendChecklist(null, list)
            store.saveDraftTitle(draft, title)
            val item = store.insertPhoto(draft, null, photo, "营地位置")
            store.insertText(draft, null, TextEdit("仅来源的路线", 0))
            store.createDraftTag(draft, "远足")
            store.completeMixedDraft(draft).also {
                store.editPhotoCaption(store.editRecord(it), item, "尚未完成的新营地")
            }
        }
        try {
            val original = runBlocking { store.openRecordContent(source)!! }
            val list = original.items[0]
            val photo = original.items[1]
            openSearch()
            findRecord(title)
            startCopy()
            awaitNode(hasText("来源有未完成修改。本次使用上次完成的内容。"))
            compose.onNodeWithText("创建副本草稿").assertIsNotEnabled()
            choose(hasTestTag("copy-item-${photo.id.value}"))
            choose(hasTestTag("copy-item-${list.id.value}"))
            val preview = hasTestTag("copy-preview-${list.id.value}")
            compose.onNodeWithTag("copy-options").performScrollToNode(preview)
            compose.onNode(preview).assertTextContains("1. 清单", substring = true)
            choose(hasTestTag("copy-up-${photo.id.value}"))
            compose.onNodeWithTag("copy-preview-${photo.id.value}").assertTextContains("1. 营地位置")
            choose(hasText("沿用清单勾选状态"))
            choose(hasText("标签 · 远足"))
            click("创建副本草稿")
            val check = hasContentDescription("完成事项 2")
            awaitNode(hasTestTag("mixed-editor")).performScrollToNode(check)
            compose.onNode(check).assertIsOff().performClick()
            compose.onNodeWithContentDescription("完成事项 1").assertIsOff()
            click("返回")
            val copy = runBlocking { store.listDrafts().single { it.recordId == null && it.title == title }.id }
            click("搜索全部记录")
            awaitNode(hasTestTag("search")).performTextReplacement(title)
            awaitNode(hasText("1 条记录"))
            click("返回")
            awaitNode(hasText("继续草稿 ·", substring = true) and hasClickAction()).performClick()
            awaitNode(hasTestTag("draft-${copy.value}")).performClick()
            awaitNode(hasTestTag("mixed-editor")).performScrollToNode(check)
            compose.onNode(check).assertIsOn()
            click("完成")
            val saved = awaitNode(hasText("已保存 · 查看"))
            val duplicate = runBlocking { store.search(title).single { it.id != source }.id }
            runBlocking {
                val result = store.openRecordContent(duplicate)!!
                assertEquals(2, result.items.size)
                assertEquals("营地位置", (result.items[0] as ContentItem.Photo).caption)
                assertEquals(listOf(false, true), (result.items[1] as ContentItem.Checklist).entries.map { it.checked })
                assertTrue(result.tags.isEmpty())
                assertEquals(original, store.openRecordContent(source))
                store.moveToTrash(source)
                assertTrue(store.deletePermanently(source).deleted)
            }
            saved.performScrollTo().performClick()
            awaitNode(hasContentDescription("记录中的照片，点击放大")).performScrollTo().performClick()
            awaitNode(hasContentDescription("照片")).assertIsDisplayed()
        } finally { clean(title) }
    }

    @Test fun cancelledSelectionAndRetriesNeverCreateExtraDrafts() {
        val title = "副本验收${System.nanoTime()}"
        val source = runBlocking {
            val draft = store.saveDraft(null, TextEdit("旧路线", 0))!!
            store.saveDraftTitle(draft, title)
            store.completeMixedDraft(draft)
        }
        val item = runBlocking { store.openRecordContent(source)!!.items.single().id }
        val option = hasTestTag("copy-item-${item.value}")
        val failWrite = AtomicBoolean(false)
        val failRead = AtomicBoolean(false)
        val drafts = object : TextDrafts by store {
            override suspend fun copyRecordToDraft(source: RecordId, expectedRevision: Long, selection: RecordCopySelection): DraftId {
                if (failWrite.getAndSet(false)) throw IOException("模拟副本写入失败")
                return store.copyRecordToDraft(source, expectedRevision, selection)
            }
        }
        val mixed = object : MixedContentDrafts by store {
            override suspend fun openMixedDraft(id: DraftId): MixedDraft? {
                if (failRead.getAndSet(false)) throw IOException("模拟已创建草稿读取失败")
                return store.openMixedDraft(id)
            }
        }
        compose.activityRule.scenario.onActivity { activity ->
            val model = FolioViewModel(drafts, store, mixed, store)
            activity.setContent { FolioApp(model) }
        }
        try {
            openSearch()
            findRecord(title)
            startCopy()
            choose(option)
            click("取消")
            assertTrue(runBlocking { store.listDrafts() }.none { it.title == title })
            startCopy()
            choose(option)
            runBlocking {
                val edit = store.editRecord(source)
                store.editTextItem(edit, item, TextEdit("新路线", 0))
                store.completeMixedDraft(edit)
            }
            click("创建副本草稿")
            awaitNode(hasText("来源已更新，请重新选择要复制的内容。"))
            startCopy()
            awaitNode(option).assertTextContains("文字 · 新路线")
            choose(option)
            failWrite.set(true)
            click("创建副本草稿")
            awaitNode(hasText("副本草稿创建失败，请重试。"))
            compose.onNode(option).assertIsOn()
            failRead.set(true)
            click("重试创建")
            awaitNode(hasText("操作已保存，草稿读取失败，请重试读取。"))
            val id = runBlocking { store.listDrafts().single { it.title == title }.id }
            click("重试")
            awaitNode(hasTestTag("editor")).assertTextContains("新路线")
            assertEquals(listOf(id), runBlocking { store.listDrafts() }.filter { it.title == title }.map { it.id })
            click("放弃修改")
            click("确认放弃")
            awaitNode(hasText("搜索全部记录"))
            assertTrue(runBlocking { store.listDrafts() }.none { it.title == title })
            assertEquals(listOf(source), runBlocking { store.search(title) }.map { it.id })
        } finally { clean(title) }
    }

    private fun openSearch() { compose.onNodeWithTag("nav-Records").performClick(); click("搜索全部记录") }
    private fun findRecord(title: String) {
        awaitNode(hasTestTag("search")).performTextReplacement(title)
        awaitNode(hasText("1 条记录"))
        val results = hasTestTag("search-results")
        compose.onNode(results).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasAnyAncestor(results), useUnmergedTree = true).performTouchInput { click() }
    }
    private fun startCopy() = awaitNode(hasText("复制为新记录") and hasClickAction() and isEnabled()).performScrollTo().performClick()
    private fun choose(matcher: SemanticsMatcher) {
        awaitNode(hasTestTag("copy-options")).performScrollToNode(matcher)
        compose.onNode(matcher).performClick()
    }
    private fun click(text: String) = awaitNode(hasText(text) and hasClickAction() and isEnabled()).performClick()
    private fun awaitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
    private fun clean(title: String) = runBlocking {
        store.listDrafts().filter { it.title == title }.forEach { store.discardDraft(it.id) }
        store.search(title).forEach { store.moveToTrash(it.id); store.deletePermanently(it.id) }
    }
}
