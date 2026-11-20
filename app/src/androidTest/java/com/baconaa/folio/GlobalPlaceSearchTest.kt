package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlobalPlaceSearchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = LocalTextStore.get(compose.activity.applicationContext)
    private val records = mutableListOf<RecordId>()

    @After fun removeRecords() = runBlocking {
        records.forEach { store.moveToTrash(it); store.deletePermanently(it) }
    }

    @Test fun chooseAndReplaceSameNamePlaceInACombinedSearch() {
        val keyword = "寻店${System.nanoTime()}"
        val placeName = "巷口咖啡${System.nanoTime()}"
        val places = mutableMapOf<String, PlaceId>()
        runBlocking {
            val context = compose.activity.applicationContext
            val photo = LocalPhotoOriginals.get(context).importPhoto(
                Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            suspend fun publish(label: String, address: String, year: Int = 2025, starred: Boolean = true, withPhoto: Boolean = true) {
                val draft = requireNotNull(store.saveDraft(null, TextEdit("$keyword $label", 0), EventTime.Year(year)))
                val place = places[address]
                if (place == null) places[address] = store.createDraftPlace(draft, PlaceInfo(placeName, address = "$address$keyword"))
                else store.selectDraftPlace(draft, place)
                if (withPhoto) store.appendPhotos(draft, listOf(photo))
                val id = store.completeMixedDraft(draft)
                records += id
                if (starred) store.setStarred(id, true)
            }
            publish("西门拍照", "西街")
            publish("东门拍照", "东街")
            publish("旧店拍照", "旧址", year = 2024)
            publish("未加星", "西街", starred = false)
            publish("仅文字", "东街", withPhoto = false)
        }

        search(keyword)
        waitCount(5)
        tapFilter("事件时间")
        compose.onNodeWithTag("time-filter-from").performTextInput("2025")
        compose.onNodeWithText("应用时间条件").performClick()
        waitCount(4)
        tapFilter("只看星标")
        waitCount(3)
        tapFilter("内容类型")
        compose.onNodeWithText("有照片").performClick()
        compose.onNodeWithText("完成选择").performClick()
        waitCount(2)

        choosePlace(places.getValue("西街"), "西街$keyword", placeName)
        waitCount(1)
        assertResult("$keyword 西门拍照")
        compose.onNodeWithText("地点操作").assertDoesNotExist()
        compose.onNodeWithTag("remove-place-filter").performScrollTo().performClick()
        waitCount(2)
        choosePlace(places.getValue("东街"), "东街$keyword", placeName)
        waitCount(1)
        assertResult("$keyword 东门拍照")
        choosePlace(places.getValue("旧址"), "旧址$keyword", placeName)
        waitCount(0)
        compose.onNodeWithText("移除时间限制").performScrollTo().performClick()
        waitCount(1)
        assertResult("$keyword 旧店拍照")
        tapFilter("清除筛选")
        waitCount(5)
        compose.onNodeWithTag("search").assertTextContains(keyword)
    }

    @Test fun returningFromDetailKeepsPlaceQueryAndScrolledResult() {
        val keyword = "逛书店${System.nanoTime()}"
        val name = "沿河书店${System.nanoTime()}"
        val place = runBlocking {
            val first = requireNotNull(store.saveDraft(null, TextEdit("$keyword 0", 0)))
            val id = store.createDraftPlace(first, PlaceInfo(name, city = "成都"))
            records += store.completeMixedDraft(first)
            for (i in 1..16) {
                val draft = requireNotNull(store.saveDraft(null, TextEdit("$keyword $i", 0)))
                store.selectDraftPlace(draft, id)
                records += store.completeMixedDraft(draft)
            }
            id
        }
        search(keyword)
        choosePlace(place, name, "成都")
        waitCount(17)
        val results = compose.onNodeWithTag("search-results")
        val target = hasText("$keyword 4") and hasAnyAncestor(hasTestTag("search-results"))
        results.performScrollToNode(target)
        val row = compose.onNode(target).assertIsDisplayed()
        val originalTop = row.fetchSemanticsNode().boundsInRoot.top - results.fetchSemanticsNode().boundsInRoot.top
        row.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("编辑").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        waitCount(17)
        compose.onNodeWithTag("search").assertTextContains(keyword)
        compose.onNodeWithTag("remove-place-filter").assertExists()
        val restoredTop = row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top - results.fetchSemanticsNode().boundsInRoot.top
        assertEquals(originalTop, restoredTop, 1f)
    }

    private fun assertResult(body: String) {
        compose.onNodeWithTag("search-results").performScrollToNode(hasText(body))
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    private fun choosePlace(id: PlaceId, query: String, displayedInfo: String) {
        tapFilter("筛选地点")
        compose.onNodeWithTag("place-search").performTextReplacement(query)
        val tag = "place-choice-${id.value}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(displayedInfo).onFirst().assertExists()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun search(keyword: String) {
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("搜索全部记录").performClick()
        compose.onNodeWithTag("search").performTextInput(keyword)
    }

    private fun tapFilter(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    private fun waitCount(count: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("$count 条记录").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
