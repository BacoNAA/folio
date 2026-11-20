package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.createAudioTestClip
import com.baconaa.folio.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class RecordPreviewNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun timelineAndSearchShowPlaceChecklistProgressAndRemainingMedia() {
        val context = compose.activity.applicationContext
        val store = LocalTextStore.get(context)
        val keyword = "相册预览" + System.nanoTime()
        val placeName = "河边书店"
        val year = EventTime.Year(2099)
        val id = runBlocking {
            val draft = requireNotNull(store.saveDraft(null, TextEdit(keyword, 0), year))
            store.createDraftPlace(draft, PlaceInfo(placeName))
            store.appendChecklist(draft, (1..4).map { index ->
                ChecklistEntry(ChecklistEntryId.create(), "事项 $index", checked = index == 1 || index == 4)
            })
            store.insertText(draft, null, TextEdit("合上书", 0))
            val photo = LocalPhotoOriginals.get(context).importPhoto(Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            store.insertPhoto(draft, null, photo)
            store.appendAudio(draft, createAudioTestClip(context).id)
            store.insertText(draft, null, TextEdit("还没念完的一段", 0))
            store.completeMixedDraft(draft)
        }
        try {
            compose.onNodeWithTag("nav-Records").performClick()
            waitForText("2099 年")
            assertPreview("records-list", keyword, placeName)
            compose.onNodeWithText("搜索全部记录").performClick()
            compose.onNodeWithTag("search").performTextInput(keyword)
            waitForText("1 条记录")
            assertPreview("search-results", keyword, placeName)
        } finally {
            runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
        }
    }

    private fun assertPreview(listTag: String, keyword: String, placeName: String) {
        val list = hasTestTag(listTag)
        compose.onNode(list).performScrollToNode(hasText(keyword))
        val inList = hasAnyAncestor(list)
        compose.onNode(inList and hasText(placeName, substring = true)).assertExists()
        compose.onNode(inList and hasText("清单 · 已完成 2/4 项")).assertExists()
        compose.onNode(inList and hasText("还有 3 项内容 · 1 张照片 · 1 段录音")).assertExists()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
