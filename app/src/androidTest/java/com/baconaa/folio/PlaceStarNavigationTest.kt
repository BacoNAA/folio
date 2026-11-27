package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaceStarNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 10000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
    private fun click(text: String) = node(hasText(text) and hasClickAction() and isEnabled()).performClick()

    @Test fun selectsSameNameByAddressAndRefreshesChangedCoordinatesOnReturn() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val model = ViewModelProvider(compose.activity)[FolioViewModel::class.java]
        val name = "星图咖啡${System.nanoTime()}"
        val records = mutableListOf<RecordId>()
        suspend fun create(address: String, coordinates: PlaceCoordinates?): PlaceId {
            val draft = store.saveDraft(null, TextEdit(address, 0))!!
            val id = store.createDraftPlace(draft, PlaceInfo(name, address = address, coordinates = coordinates))
            records += store.completeMixedDraft(draft)
            return id
        }
        try {
            val (west, east, unknown) = runBlocking {
                val shared = PlaceCoordinates(30.0, 120.0)
                listOf(create("西门", shared), create("东门", shared), create("三楼", null))
            }
            compose.onNodeWithTag("nav-Records").performClick()
            click("按地点")
            node(hasTestTag("place-search")).performTextInput(name)
            compose.runOnUiThread { compose.activity.currentFocus?.let {
                val imm = compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            } }
            click("地点星图")
            node(hasContentDescription("${name}等 2 处地点")).performClick()
            val entry = hasTestTag("star-book-${east.value}")
            node(hasTestTag("star-chart")).performScrollToNode(entry)
            node(entry).assert(hasText("东门")).performClick()
            compose.runOnIdle { assertEquals(east, model.state.placeBook?.id) }
            runBlocking {
                val place = store.openPlace(east)!!
                store.updatePlace(east, place.info.copy(coordinates = PlaceCoordinates(30.5, 121.0)))
            }
            click("返回")
            node(hasTestTag("star-${east.value}")).assertExists()
            node(hasTestTag("star-${west.value}")).assertExists()
            node(hasTestTag("star-chart")).performScrollToNode(entry)
            node(entry).assert(hasText("东门"))
            click("地点列表")
            node(hasTestTag("places-list")).performScrollToNode(hasTestTag("place-open-${unknown.value}"))
            node(hasTestTag("place-open-${unknown.value}")).performClick()
            compose.runOnIdle { assertEquals(unknown, model.state.placeBook?.id) }
        } finally {
            runBlocking { records.forEach { store.moveToTrash(it); store.deletePermanently(it) } }
        }
    }
}
