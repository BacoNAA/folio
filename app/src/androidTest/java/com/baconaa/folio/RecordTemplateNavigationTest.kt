package com.baconaa.folio

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioApp
import com.baconaa.folio.ui.FolioViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class RecordTemplateNavigationTest : PhotoStoreFixture() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun extractFillOnlyTheNewReviewAndKeepAnEarlierDraftWhenReordering() {
        val suffix = System.nanoTime()
        val title = "探店来源$suffix"
        val templateName = "探店模板$suffix"
        val source = runBlocking {
            val photo = importPhoto(Color.RED).id
            val draft = store.appendPhotos(null, listOf(photo))
            store.insertText(draft, null, TextEdit("旧菜品", 0))
            store.saveDraftTitle(draft, title)
            store.completeMixedDraft(draft)
        }
        compose.activityRule.scenario.onActivity { activity ->
            val model = FolioViewModel(store, store, store, store)
            activity.setContent { FolioApp(model) }
        }
        compose.onNodeWithTag("nav-Records").performClick()
        click("搜索全部记录")
        await(hasTestTag("search")).performTextReplacement(title)
        await(hasText("1 条记录"))
        val results = hasTestTag("search-results")
        compose.onNode(results).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasAnyAncestor(results), useUnmergedTree = true).performTouchInput { click() }
        click("提取为模板")
        option("template-name").performTextReplacement(templateName)
        option("template-field-0").performTextReplacement("菜品照片")
        option("template-field-1").performTextReplacement("口味感受")
        click("保存模板")
        await(hasText("模板已保存"))
        val template = runBlocking { store.listTemplates().single { it.name == templateName } }
        fun start() = option("use-template-${template.id.value}").performClick()
        start()
        await(hasText("完成") and hasClickAction()).assertIsNotEnabled()
        await(hasTestTag("mixed-editor")).performScrollToNode(hasTestTag("editor"))
        compose.onNodeWithTag("editor").performTextReplacement("这次汤底清甜$suffix")
        click("完成")
        await(hasText("已保存 · 查看"))
        val record = runBlocking { store.search("这次汤底清甜$suffix").single().id }
        click("记录模板")
        start()
        await(hasText("菜品照片"))
        val oldDraft = runBlocking { store.listDrafts().single { store.openMixedDraft(it.id)!!.items.any { f -> f.fieldName == "口味感受" } }.id }
        click("返回")
        compose.onNodeWithTag("nav-Capture").performClick()
        click("记录模板")
        option("edit-template-${template.id.value}").performClick()
        option("template-up-1").performClick()
        click("保存模板")
        await(hasText("模板已保存"))
        start()
        await(hasTestTag("editor")).assertIsDisplayed()
        runBlocking {
            assertEquals(listOf("菜品照片", "口味感受"), store.openMixedDraft(oldDraft)!!.items.map { it.fieldName })
            val newest = store.listDrafts().first().id
            assertNotEquals(oldDraft, newest)
            assertEquals(listOf("口味感受", "菜品照片"), store.openMixedDraft(newest)!!.items.map { it.fieldName })
            assertEquals(listOf("口味感受"), store.openRecordContent(record)!!.items.map { it.fieldName })
            assertTrue(store.openRecordContent(record)!!.photos.isEmpty())
            assertEquals(1, store.openRecordContent(source)!!.photos.size)
        }
    }

    @Test fun blankTemplateRetainsEditsOnWriteFailureAndReopensSameDraftAfterReadFailure() {
        val name = "路边声音${System.nanoTime()}"
        val failSave = AtomicBoolean(true)
        val failRead = AtomicBoolean(false)
        val templates = object : RecordTemplates by store {
            override suspend fun saveTemplate(previous: RecordTemplate?, name: String, fields: List<TemplateField>, tags: Set<TagId>): RecordTemplate {
                if (failSave.getAndSet(false)) throw IOException("写入失败")
                return store.saveTemplate(previous, name, fields, tags)
            }
        }
        val mixed = object : MixedContentDrafts by store {
            override suspend fun openMixedDraft(id: DraftId): MixedDraft? {
                if (failRead.getAndSet(false)) throw IOException("读取失败")
                return store.openMixedDraft(id)
            }
        }
        compose.activityRule.scenario.onActivity { activity ->
            val model = FolioViewModel(store, store, mixed, store, templates = templates)
            activity.setContent { FolioApp(model) }
        }
        click("记录模板")
        click("新建模板")
        option("template-name").performTextReplacement(name)
        await(hasTestTag("template-options")).performScrollToNode(hasText("添加录音"))
        click("添加录音")
        option("template-field-0").performTextReplacement("声响$name")
        click("保存模板")
        await(hasTestTag("template-options")).performScrollToNode(hasText("模板保存失败，已保留当前填写，请重试。"))
        assertTrue(runBlocking { store.listTemplates() }.none { it.name == name })
        click("保存模板")
        await(hasText("模板已保存"))
        val template = runBlocking { store.listTemplates().single { it.name == name } }
        failRead.set(true)
        option("use-template-${template.id.value}").performClick()
        await(hasText("操作已保存，草稿读取失败，请重试读取。"))
        val before = runBlocking { store.listDrafts().map { it.id } }
        click("重试")
        await(hasText("声响$name"))
        assertEquals(before, runBlocking { store.listDrafts().map { it.id } })
        click("放弃修改")
        click("确认放弃")
        await(hasText("搜索全部记录"))
        assertEquals(before.size - 1, runBlocking { store.listDrafts().size })
    }

    private fun option(tag: String): SemanticsNodeInteraction {
        val match = hasTestTag(tag)
        await(hasTestTag("template-options")).performScrollToNode(match)
        return await(match)
    }
    private fun click(text: String) {
        val node = await(hasText(text) and hasClickAction() and isEnabled())
        if (text == "记录模板" || text == "提取为模板") node.performScrollTo()
        node.performClick()
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
}
