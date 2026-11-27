package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ObservationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    @Test fun placeBookSavesObservationAndReturnsFromItsOriginalRecord() {
        val (record, place, item) = runBlocking {
            val draft = store.saveDraft(null, TextEdit("面馆又开了", 0), EventTime.Month(YearMonth.of(2024, 6)))!!
            val place = store.createDraftPlace(draft, PlaceInfo("老面馆${System.nanoTime()}"))
            val record = store.completeMixedDraft(draft)
            Triple(record, store.openPlace(place)!!, store.openRecordContent(record)!!.items.single().id)
        }
        try {
            compose.runOnIdle { ViewModelProvider(compose.activity)[FolioViewModel::class.java].openPlace(place) }
            node(hasText("地点操作")).performClick()
            node(hasText("变化轨道")).performClick()
            node(hasText("添加观察")).performClick()
            node(hasTestTag("observe-record-${record.value}")).performClick()
            node(hasTestTag("observe-item-${item.value}")).performClick()
            node(hasTestTag("observation-note")).performTextInput("重新开张")
            node(hasText("保存观察")).performClick()
            node(hasText("重新开张")).assertExists()
            node(hasText("原记录")).performClick()
            node(hasText("面馆又开了")).assertExists()
            node(hasText("返回")).performClick()
            node(hasText("2024 年 6 月")).assertExists()
            node(hasText("移出观察")).performClick()
            node(hasText("还没有观察")).assertExists()
        } finally { runBlocking { store.moveToTrash(record); store.deletePermanently(record) } }
    }
}
