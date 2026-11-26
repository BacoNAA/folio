package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.records.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DateReviewNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun button(label: String): SemanticsNodeInteraction {
        val matcher = hasText(label) and hasClickAction()
        compose.waitUntil(label, 10000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    @Test fun opensOriginalAndSeasonExclusionSurvivesReentry() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val today = LocalDate.now()
        val autumn = EventTime.Season(today.year - 2, NamedSeason.AUTUMN)
        val ids = mutableListOf<RecordId>()
        try {
            runBlocking {
                listOf(autumn to "秋日松果", EventTime.Description("大学时") to "大学散步").forEach { (time, text) ->
                    ids += store.completeMixedDraft(store.saveDraft(null, TextEdit(text, 0), time)!!)
                }
            }
            compose.onNodeWithTag("nav-Records").performClick()
            button("日期回看").performClick()
            button("秋天").performClick()
            compose.onNodeWithTag("date-review").performScrollToNode(hasText("秋日松果"))
            button("秋日松果").performClick()
            button("返回").performClick()
            compose.onNodeWithTag("date-review").performScrollToNode(hasText("从季节回看排除"))
            button("从季节回看排除").performClick()
            button("管理本季已排除记录").assertExists()
            button("返回").performClick()
            button("日期回看").performClick()
            button("管理本季已排除记录").performClick()
            compose.onNodeWithTag("date-review").performScrollToNode(hasText("恢复季节回看"))
            button("恢复季节回看").performClick()
            button("秋日松果").assertExists()
            runBlocking {
                assertEquals(ids.first(), store.search("秋日松果").single().id)
                assertFalse(store.reviewDates(DateReviewQuery(today, NamedSeason.AUTUMN)).single { it.record.id == ids.first() }.excluded)
            }
        } finally {
            runBlocking { ids.forEach { store.moveToTrash(it); store.deletePermanently(it) } }
        }
    }
}
