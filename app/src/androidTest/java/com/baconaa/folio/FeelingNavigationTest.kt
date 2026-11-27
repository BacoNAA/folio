package com.baconaa.folio

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioApp
import com.baconaa.folio.ui.FolioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class FeelingNavigationTest : PhotoStoreFixture() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editAVisitAndFindItByTheDisplayedDegree() {
        val scale = runBlocking { store.saveFeelingScale(null, "辣度", listOf("不辣", "微辣", "中辣", "很辣", "特辣")) }
        val record = runBlocking {
            val draft = store.saveDraft(null, TextEdit("面馆的晚餐", 0))!!
            store.setDraftFeeling(draft, scale.choice(3))
            store.completeMixedDraft(draft)
        }
        compose.activityRule.scenario.onActivity { activity ->
            val model = FolioViewModel(store, store, store, store)
            activity.setContent { FolioApp(model) }
            model.openRecord(record)
        }
        await(hasText("辣度 · 3/5 · 中辣")).assertExists()
        await(hasText("编辑") and hasClickAction()).performClick()
        val editor = await(hasTestTag("mixed-editor"))
        editor.performScrollToNode(hasTestTag("editor-clues"))
        await(hasTestTag("editor-clues")).performClick()
        editor.performScrollToNode(hasText("本次感受") and hasClickAction())
        await(hasText("本次感受") and hasClickAction()).performClick()
        val project = hasTestTag("feeling-scale-${scale.id.value}")
        select(project)
        select(hasText("4/5 · 很辣") and hasClickAction())
        await(hasText("当前 · 4/5 · 很辣")).assertExists()
        await(hasText("完成") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await(hasText("完成") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { runBlocking { store.openRecordContent(record)!!.feelings.single().choice == scale.choice(4) } }
        await(hasText("辣度 · 4/5 · 很辣")).assertExists()
        await(hasText("返回") and hasClickAction()).performClick()
        await(hasTestTag("nav-Records")).performClick()
        await(hasText("搜索全部记录") and hasClickAction()).performClick()
        await(hasText("筛选感受") and hasClickAction()).performScrollTo().performClick()
        select(project)
        select(hasText("4/5 · 很辣") and hasClickAction())
        await(hasText("辣度 · 4/5 · 很辣 ×")).assertExists()
        await(hasText("1 条记录")).assertExists()
        await(hasTestTag("search-results")).performScrollToNode(hasText("面馆的晚餐"))
        assertEquals(listOf(scale.choice(4)), runBlocking { store.historicalFeelings(scale.id) })
    }

    @Test fun placeBookCanKeepAnImpressionAndDefineANewScaleWithoutChangingItsVisit() {
        val (place, record) = runBlocking {
            val draft = store.saveDraft(null, TextEdit("面馆的午餐", 0))!!
            val id = store.createDraftPlace(draft, PlaceInfo("常去的面馆"))
            val record = store.completeMixedDraft(draft)
            store.openPlace(id)!! to record
        }
        compose.activityRule.scenario.onActivity { activity ->
            val model = FolioViewModel(store, store, store, store)
            activity.setContent { FolioApp(model) }
            model.openPlace(place)
        }
        await(hasText("地点操作") and hasClickAction()).performClick()
        await(hasText("地点整体印象") and hasClickAction()).performClick()
        await(hasText("地点印象") and hasSetTextAction()).performTextInput("愿意再来")
        select(hasText("保存地点印象") and hasClickAction())
        compose.waitUntil(10_000) { runBlocking { store.placeImpression(place.id) == "愿意再来" } }
        select(hasText("新建刻度") and hasClickAction())
        await(hasText("刻度名称") and hasSetTextAction()).performTextInput("喜爱程度")
        await(hasText("等级含义，每行一级") and hasSetTextAction()).performTextInput("一般\n喜欢")
        await(hasText("保存刻度") and hasClickAction() and isEnabled()).performClick()
        select(hasText("2/2 · 喜欢") and hasClickAction())
        await(hasText("当前 · 2/2 · 喜欢")).assertExists()
        await(hasText("完成") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await(hasText("地点整体印象 · 愿意再来")).assertExists()
        runBlocking {
            assertTrue(store.openRecordContent(record)!!.feelings.isEmpty())
            assertEquals("喜欢", store.placeFeelings(place.id).single().label)
        }
    }

    private fun select(matcher: SemanticsMatcher) {
        await(hasTestTag("feeling-options")).performScrollToNode(matcher)
        await(matcher).performClick()
    }

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
}
