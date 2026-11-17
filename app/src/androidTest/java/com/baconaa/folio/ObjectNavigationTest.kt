package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ObjectNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)

    @Test fun createBothKindsRestorePublishSearchAliasAndEditSharedIdentity() {
        val suffix = System.nanoTime().toString()
        val text = "对象正文$suffix"
        val personName = "小李$suffix"
        val alias = "李姐$suffix"
        compose.onNodeWithTag("write").performClick()
        compose.onNodeWithTag("editor").performTextInput(text)
        waitText("草稿已保存")
        editorClick("editor-clues")
        editorClick("edit-PERSON")
        compose.onNodeWithText("新建人物").performClick()
        compose.onNodeWithTag("object-name").performTextInput(personName)
        compose.onNodeWithTag("object-note").performScrollTo().performTextInput("同事")
        compose.onNodeWithTag("object-aliases").performScrollTo().performTextInput(alias)
        compose.onNodeWithText("保存对象").performClick()
        waitText("本草稿新对象")
        assertFalse(runBlocking { store.listObjects() }.any { it.info.name == personName })
        compose.onNodeWithText("关闭人物").performClick()
        editorClick("edit-THING")
        compose.onNodeWithText("新建物件").performClick()
        compose.onNodeWithTag("object-name").performTextInput("搪瓷杯$suffix")
        compose.onNodeWithTag("object-note").performScrollTo().performTextInput("白色")
        compose.onNodeWithText("保存对象").performClick()
        waitText("本草稿新对象")
        compose.onNodeWithText("关闭物件").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("完成").performClick()
        waitText("已保存 · 查看")
        val record = runBlocking { store.queryRecords(text).single() }
        assertEquals(2, record.objects.size)
        val person = record.objects.single { it.kind == ObjectKind.PERSON }
        val times = runBlocking { store.openRecordContent(record.id) }!!.times
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextInput(alias)
        waitText("1 条记录")
        compose.onNodeWithText("筛选人物").performScrollTo().performClick()
        compose.onNodeWithTag("object-search").performTextInput(personName)
        compose.onNodeWithTag("object-choices").performScrollToNode(hasTestTag("object-edit-${person.id.value}"))
        compose.onNodeWithTag("object-edit-${person.id.value}").performClick()
        compose.onNodeWithTag("object-name").performTextReplacement("李阿姨$suffix")
        compose.onNodeWithText("保存对象").performClick()
        waitText("关闭人物")
        compose.onNodeWithText("关闭人物").performClick()
        waitText("1 条记录")
        val updated = runBlocking { store.openRecordContent(record.id) }!!
        assertEquals(times, updated.times)
        assertEquals(person.id, updated.objects.first().id)
        assertEquals("李阿姨$suffix", updated.objects.first().info.name)
        assertEquals(listOf(alias), updated.objects.first().info.aliases)
        clean(record.id)
    }

    @Test fun peopleAndThingFiltersIntersectAndRemovingOnePreservesQueryAndOtherKind() {
        val text = "组合对象${System.nanoTime()}"
        val (both, people, thing) = runBlocking {
            val draft = store.saveDraft(null, TextEdit("$text，送杯子", 0))!!
            val person = store.createDraftObject(draft, ObjectKind.PERSON, ObjectInfo("外婆$text", "家人"))
            val cup = store.createDraftObject(draft, ObjectKind.THING, ObjectInfo("搪瓷杯$text", "白色"))
            val both = store.completeMixedDraft(draft)
            val peopleDraft = store.saveDraft(null, TextEdit("$text，吃饭", 0))!!
            store.saveDraftObjects(peopleDraft, listOf(person))
            val people = store.completeMixedDraft(peopleDraft)
            val thingDraft = store.saveDraft(null, TextEdit("$text，配盖", 0))!!
            store.saveDraftObjects(thingDraft, listOf(cup))
            Triple(both, people, store.completeMixedDraft(thingDraft))
        }
        val objects = runBlocking { store.openRecordContent(both) }!!.objects
        val person = objects.single { it.kind == ObjectKind.PERSON }
        val cup = objects.single { it.kind == ObjectKind.THING }
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextInput(text)
        waitText("3 条记录")
        filter("人物", person, text)
        waitText("2 条记录")
        filter("物件", cup, text)
        waitText("1 条记录")
        compose.onNodeWithTag("remove-object-${person.id.value}").performScrollTo().performClick()
        waitText("2 条记录")
        compose.onNodeWithTag("search").assertTextContains(text)
        compose.onNodeWithTag("remove-object-${cup.id.value}").assertExists()
        compose.onNodeWithText("清除筛选").performScrollTo().performClick()
        waitText("3 条记录")
        compose.onNodeWithTag("search").assertTextContains(text)
        listOf(both, people, thing).forEach(::clean)
    }

    private fun filter(kind: String, value: RecordObject, query: String) {
        compose.onNodeWithText("筛选$kind").performScrollTo().performClick()
        compose.onNodeWithTag("object-search").performTextInput(query)
        compose.onNodeWithTag("object-choices").performScrollToNode(hasTestTag("object-choice-${value.id.value}"))
        compose.onNodeWithTag("object-choice-${value.id.value}").performClick()
        compose.onNodeWithText("关闭$kind").performClick()
    }
    private fun editorClick(tag: String) {
        compose.onNodeWithTag("mixed-editor").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun clean(id: RecordId) = runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
}
