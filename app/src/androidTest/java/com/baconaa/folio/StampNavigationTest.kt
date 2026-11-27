package com.baconaa.folio

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.DocumentsContract
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.StampArtwork
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StampNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        return compose.onNode(matcher)
    }

    @Test fun cropPhotoKeepSelectionOnCancelThenReplaceSharedStampWithImportedDrawing() {
        val image = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.RED)
        for (x in 50 until 100) for (y in 0 until 40) image.setPixel(x, y, Color.BLUE)
        val file = File.createTempFile("apron", ".png", File(context.cacheDir, "camera").apply { mkdirs() })
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        val name = "家的味道${System.nanoTime()}"
        val renamed = "外婆的厨房${System.nanoTime()}"
        val records = mutableListOf<RecordId>()
        val draft = runBlocking { store.appendPhotos(null, listOf(LocalPhotoOriginals.get(context).importPhoto(uri))) }
        val item = runBlocking { store.openMixedDraft(draft)!!.items.single().id }
        val preview = runBlocking { store.listDrafts().single { it.id == draft } }
        var supplied: Uri? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:"))
                return supplied?.let { Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(it)) }
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.runOnIdle { ViewModelProvider(compose.activity)[FolioViewModel::class.java].resume(preview) }
            for (tag in listOf("editor-clues", "edit-tags")) {
                node(hasTestTag("mixed-editor")).performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
            }
            node(hasText("制作图章") and hasClickAction()).performClick()
            node(hasTestTag("stamp-name")).performTextInput(name)
            node(hasTestTag("stamp-photo-${item.value}")).performScrollTo().performClick()
            node(hasTestTag("stamp-crop-x")).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            node(hasText("从文件选择照片或小画")).performScrollTo().performClick()
            compose.waitUntil(15_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true }
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil("回到图章编辑器", 15_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
            }
            node(hasTestTag("stamp-crop-x")).assertExists()
            context.openOrCreateDatabase("folio.db", android.content.Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""CREATE TRIGGER fail_g06_stamp BEFORE INSERT ON tag_stamps
                              BEGIN SELECT RAISE(ABORT, 'test_write_failure'); END""")
                try {
                    node(hasText("保存图章") and isEnabled()).performClick()
                    compose.waitUntil("显示保存失败提示", 10_000) {
                        compose.onAllNodesWithText("图章保存失败，请重试。").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(runBlocking { store.openDraftTags(draft)!!.isEmpty() })
                    node(hasTestTag("stamp-name")).assertTextContains(name)
                } finally { db.execSQL("DROP TRIGGER fail_g06_stamp") }
            }
            node(hasText("保存图章") and isEnabled()).performClick()
            node(hasText("关闭标签") and hasClickAction()).performClick()
            node(hasText("完成") and isEnabled()).performClick()
            node(hasText("已保存 · 查看")).performClick()
            node(hasContentDescription("图章 · $name")).assertExists()
            val tag = runBlocking { store.listTags(name).single() }
            assertEquals(Color.BLUE, StampArtwork.decode(runBlocking { store.readStamp(tag)!! }).getPixel(48, 48))
            records += runBlocking { store.queryRecords(name).single().id }
            records += runBlocking {
                val second = store.saveDraft(null, TextEdit("厨房菜谱", 0))!!
                store.saveDraftTags(second, listOf(tag.id))
                store.completeMixedDraft(second)
            }
            node(hasText("返回")).performClick()
            node(hasTestTag("nav-Records")).performClick()
            node(hasText("搜索全部记录")).performClick()
            node(hasText("筛选标签")).performScrollTo().performClick()
            node(hasTestTag("tag-choices")).performScrollToNode(hasTestTag("edit-stamp-${tag.id.value}"))
            node(hasTestTag("edit-stamp-${tag.id.value}")).performClick()
            node(hasTestTag("stamp-name")).performTextReplacement(renamed)
            supplied = uri
            node(hasText("从文件选择照片或小画")).performScrollTo().performClick()
            node(hasText("保留整幅")).assertExists()
            node(hasText("保存图章") and isEnabled()).performClick()
            node(hasText("关闭标签") and hasClickAction()).performClick()
            node(hasTestTag("search")).performTextInput(renamed)
            node(hasText("2 条记录")).assertExists()
            val whole = StampArtwork.decode(runBlocking { store.readStamp(store.listTags(renamed).single())!! })
            assertEquals(Color.RED, whole.getPixel(8, 48))
            assertEquals(Color.BLUE, whole.getPixel(88, 48))
            assertEquals(Color.TRANSPARENT, whole.getPixel(48, 4))
        } finally {
            instrumentation.removeMonitor(monitor)
            runBlocking {
                store.discardDraft(draft)
                records.forEach { store.moveToTrash(it); store.deletePermanently(it) }
            }
            file.delete()
        }
    }
}
