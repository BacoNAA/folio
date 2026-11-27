package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomStateNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
    private fun click(label: String) = node(hasText(label) and hasClickAction()).performClick()

    @Test fun createBookStateKeepOneChangeThenFindItsRecordByItsIndependentState() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val suffix = System.nanoTime().toString().takeLast(8)
        val title = "读书记录$suffix"
        val bookName = "纸书$suffix"
        val pending = CustomState("pending-$suffix", "待整理$suffix", "◇")
        val reading = CustomState("reading-$suffix", "在读$suffix", "▤")
        val (record, book) = runBlocking {
            val draft = store.saveDraft(null, TextEdit(title, 0))!!
            val id = store.createDraftObject(draft, ObjectKind.THING, ObjectInfo(bookName))
            val record = store.completeMixedDraft(draft)
            store.defineState(reading)
            store.defineState(pending)
            store.setState(StateTarget(record), null, pending.id, "record", null)
            val book = StateTarget(StateTargetKind.THING, id.value)
            store.setState(book, null, reading.id, "book", null)
            record to book
        }
        try {
            compose.onNodeWithTag("nav-Records").performClick()
            click("人物与物件")
            compose.onNodeWithTag("object-directory-query").performTextInput(bookName)
            node(hasText(bookName) and hasText("物件") and hasClickAction()).performClick()
            click("当前状态 · ${reading.label}")
            click("新建状态")
            node(hasText("状态名称") and hasSetTextAction()).performTextInput("读完$suffix")
            node(hasText("图案（符号或表情）") and hasSetTextAction()).performTextInput("✓")
            click("创建")
            click("留下这次变化")
            node(hasText("变化日期（年-月-日）") and hasSetTextAction()).performTextReplacement("2025-03-01")
            node(hasText("变化说明") and hasSetTextAction()).performTextInput("第三章读完")
            click("保存状态")
            click("当前状态 · ✓ 读完$suffix")
            node(hasText("第三章读完")).assertExists()
            click("✓ 读完$suffix")
            click(reading.label)
            click("保存状态")
            node(hasText("当前状态 · ${reading.label}") and hasClickAction()).assertExists()
            click("返回")
            click("返回")
            click("按状态查看")
            click("选择状态")
            click(pending.label)
            click("记录 · $title")
            click("当前状态 · ${pending.label}")
            node(hasText("还没有保留的变化")).assertExists()
            runBlocking {
                val snapshot = store.readStates(book)
                assertEquals(reading, snapshot.current)
                assertEquals("第三章读完", snapshot.history.single().entry.note)
                assertEquals("2025-03-01", snapshot.history.single().entry.date.toString())
            }
        } finally {
            runBlocking { store.moveToTrash(record); store.deletePermanently(record) }
        }
    }
}
