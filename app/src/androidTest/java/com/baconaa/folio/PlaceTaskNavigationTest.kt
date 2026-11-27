package com.baconaa.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PlaceTaskNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun addTaskAndWriteExperienceFromPlaceBook() {
        val store = LocalTextStore.get(compose.activity.applicationContext)
        val place = runBlocking {
            val d = store.saveDraft(null, TextEdit("点菜", 0))!!
            val p = store.createDraftPlace(d, PlaceInfo("面馆"))
            store.completeMixedDraft(d)
            store.openPlace(p)!!
        }
        fun node(label: String): SemanticsNodeInteraction {
            compose.waitUntil(15000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            return compose.onNodeWithText(label)
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[FolioViewModel::class.java].openPlace(place) }
        node("地点操作").performClick()
        node("下次想试").performClick()
        node("下次想做的事").performTextInput("豆花鱼")
        node("添加事项").performClick()
        node("豆花鱼").assertExists()
        node("补一条体验").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor").performTextInput("鱼很嫩")
        node("完成").performClick()
        node("已保存 · 查看").assertExists()
        runBlocking { check(store.placeTasks.read(place.id).single().experiences.single().label == "鱼很嫩") }
    }
}
