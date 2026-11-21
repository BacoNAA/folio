package com.baconaa.folio

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioApp
import com.baconaa.folio.ui.FolioViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        compose.onNodeWithText("搜索范围").performClick()
        compose.onNodeWithText("只查关联到「$placeName」的正式记录。").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
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
        visitResultAndReturn("$keyword 4", 17)
        compose.onNodeWithTag("search").assertTextContains(keyword)
        compose.onNodeWithTag("remove-place-filter").assertExists()
    }

    @Test fun tagAlternativesRemainIndependentOfKeywordAndStar() {
        val keyword = "咖啡${System.nanoTime()}"
        val suffix = keyword.takeLast(4)
        val travel = "旅行$suffix"
        val dining = "聚餐$suffix"
        val (travelTag, diningTag) = runBlocking {
            suspend fun draft(label: String) = requireNotNull(store.saveDraft(null, TextEdit("$keyword $label", 0)))
            suspend fun publish(draft: DraftId, starred: Boolean = true) {
                val record = store.completeMixedDraft(draft)
                records += record
                if (starred) store.setStarred(record, true)
            }
            val travelDraft = draft("旅行记录")
            val travelId = store.createDraftTag(travelDraft, travel)
            publish(travelDraft)
            val diningDraft = draft("聚餐 0")
            val diningId = store.createDraftTag(diningDraft, dining)
            publish(diningDraft)
            for (index in 1..16) {
                val anotherDiningDraft = draft("聚餐 $index")
                store.saveDraftTags(anotherDiningDraft, listOf(diningId))
                publish(anotherDiningDraft, starred = index != 16)
            }
            publish(draft("无标签"))
            travelId to diningId
        }

        search(keyword)
        waitCount(19)
        tapFilter("只看星标")
        waitCount(18)
        tapFilter("筛选标签")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasAnyAncestor(hasTestTag("tag-choices")) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        chooseTag(travelTag)
        chooseTag(diningTag)
        compose.onNodeWithText("关闭标签").performClick()
        waitCount(17)
        compose.onNodeWithText("标签：").assertIsDisplayed()
        compose.onNodeWithText("或").assertIsDisplayed()
        assertResult("$keyword 旅行记录")
        assertResult("$keyword 聚餐 0")

        compose.onNodeWithText("$travel ×").performScrollTo().performClick()
        waitCount(16)
        compose.onNodeWithText("$travel ×").assertDoesNotExist()
        compose.onNodeWithText("或").assertDoesNotExist()
        // 用中间结果核对滚动偏移，留出列表末尾的空间供键盘收起后重新布局。
        visitResultAndReturn("$keyword 聚餐 7", 16)
        compose.onNodeWithTag("search").assertTextContains(keyword)
        compose.onNodeWithText("只看星标").assertIsSelected()
        compose.onNodeWithText("$dining ×").assertIsDisplayed()
    }

    @Test fun changingFiltersDoesNotOfferResultsFromThePreviousQuery() {
        val keyword = "筛选读取${System.nanoTime()}"
        val oldResult = "$keyword 未加星"
        val currentResult = "$keyword 已加星"
        runBlocking {
            records += store.completeMixedDraft(requireNotNull(store.saveDraft(null, TextEdit(oldResult, 0))))
            val starred = store.completeMixedDraft(requireNotNull(store.saveDraft(null, TextEdit(currentResult, 0))))
            records += starred
            store.setStarred(starred, true)
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : RecordBrowsing by store {
            override suspend fun queryRecords(query: RecordQuery): RecordSearchResult {
                if (query.starredOnly) {
                    started.complete(Unit)
                    withTimeout(15_000) { release.await() }
                }
                return store.queryRecords(query)
            }
        }
        compose.runOnUiThread {
            val model = FolioViewModel(store, store, store, delayed)
            compose.activity.setContent { FolioApp(model) }
        }
        search(keyword)
        waitCount(2)
        assertResult(oldResult)
        tapFilter("只看星标")
        try {
            runBlocking { withTimeout(10_000) { started.await() } }
            compose.onNodeWithText(oldResult).assertDoesNotExist()
        } finally {
            release.complete(Unit)
        }
        waitCount(1)
        assertResult(currentResult)
        compose.onNodeWithText(oldResult).assertDoesNotExist()
    }

    private fun visitResultAndReturn(body: String, count: Int) {
        val results = compose.onNodeWithTag("search-results")
        val target = hasText(body) and hasAnyAncestor(hasTestTag("search-results"))
        results.performScrollToNode(target)
        val row = compose.onNode(target).assertIsDisplayed()
        val originalTop = row.fetchSemanticsNode().boundsInRoot.top - results.fetchSemanticsNode().boundsInRoot.top
        row.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("编辑").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回").performClick()
        waitCount(count)
        val restoredTop = row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top - results.fetchSemanticsNode().boundsInRoot.top
        assertEquals(originalTop, restoredTop, 1f)
    }

    private fun assertResult(body: String) {
        compose.onNodeWithTag("search-results").performScrollToNode(hasText(body))
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    private fun chooseTag(id: TagId) {
        val choice = hasTestTag("tag-choice-${id.value}")
        compose.onNodeWithTag("tag-choices").performScrollToNode(choice)
        compose.onNode(choice).assertIsEnabled().performClick()
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
