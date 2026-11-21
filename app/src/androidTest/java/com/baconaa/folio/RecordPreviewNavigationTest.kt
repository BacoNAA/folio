package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.createAudioTestClip
import com.baconaa.folio.records.*
import java.io.File
import java.nio.file.Files
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

    @Test fun photoViewerRetriesReadAndSupportsZoomUsingButtons() {
        val context = compose.activity.applicationContext
        val store = LocalTextStore.get(context)
        val keyword = "照片缩放${System.nanoTime()}"
        val (id, photoId) = runBlocking {
            val photo = LocalPhotoOriginals.get(context).importPhoto(
                Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            val draft = requireNotNull(store.saveDraft(null, TextEdit(keyword, 0)))
            store.appendPhotos(draft, listOf(photo))
            store.completeMixedDraft(draft) to photo
        }
        val original = File(context.filesDir, "photo-originals/${photoId.value}").toPath()
        val unavailable = original.resolveSibling("${original.fileName}.unavailable")
        try {
            Files.move(original, unavailable)
            compose.onNodeWithTag("nav-Records").performClick()
            compose.onNodeWithText("搜索全部记录").performClick()
            compose.onNodeWithTag("search").performTextInput(keyword)
            waitForText("1 条记录")
            val results = hasTestTag("search-results")
            compose.onNode(results).performScrollToNode(hasText(keyword))
            compose.onNode(hasText(keyword) and hasAnyAncestor(results), useUnmergedTree = true)
                .performTouchInput { click() }
            waitForText("编辑")
            waitForText("照片读取失败。")
            Files.move(unavailable, original)
            compose.onNodeWithText("重试读取照片").performScrollTo().performClick()
            val preview = "记录中的照片，点击放大"
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(preview).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(preview).performScrollTo().performClick()

            val photo = compose.onNodeWithContentDescription("照片")
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 100%"))
            compose.onNodeWithText("缩小").assertIsNotEnabled()
            compose.onNodeWithText("放大").performClick()
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 150%"))
            compose.onNodeWithText("缩小").performClick()
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 100%"))
            compose.onNodeWithText("放大").performClick()
            compose.onNodeWithText("还原").performClick()
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 100%"))
            compose.onNodeWithText("关闭照片").performClick()
            photo.assertDoesNotExist()
            compose.onNodeWithContentDescription(preview).assertIsDisplayed()
        } finally {
            if (Files.exists(unavailable)) Files.move(unavailable, original)
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
