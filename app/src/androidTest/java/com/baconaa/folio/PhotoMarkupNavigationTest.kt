package com.baconaa.folio

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.records.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoMarkupNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)
    private val board get() = compose.onNodeWithContentDescription("照片圈画画布")
    private val viewer get() = compose.onNodeWithContentDescription("照片")
    // 300×200 原始像素中的蓝色总开关 (0.28, 0.63)、绿色保险丝 (0.70, 0.32) 经 EXIF 横向翻转再顺转 90°。
    private val blue = Offset(0.37f, 0.72f)
    private val green = Offset(0.68f, 0.30f)

    @Test fun sharedPhotoMarksStayOnTheirObjectsAcrossRotationZoomAndDeletion() {
        val image = importPanel()
        val original = LocalPhotoOriginals.get(context).openOriginal(image).use { it.readBytes() }
        val fuse = "保险丝${System.nanoTime()}"
        val main = "总开关${System.nanoTime()}"
        val b = record(image, fuse)
        val a = record(image, main)
        val previousOrientation = compose.activity.requestedOrientation
        try {
            openSearch()
            findRecord(fuse)
            editPhoto()
            touch(board, green)
            click("保存标注")
            publish()

            click("返回")
            findRecord(main)
            editPhoto()
            assertMarker(board, blue, marked = false)
            touch(board, blue, drag = true)
            touch(board, green)
            click("撤销一笔")
            assertMarker(board, green, marked = false)
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            awaitNode(hasContentDescription("照片圈画画布"))
            board.performTouchInput { click(Offset(2f, 2f)) } // 横屏黑边
            assertMarker(board, blue, marked = true)
            click("放大")
            assertMarker(board, blue, marked = true, zoom = 1.5f)
            click("保存标注")
            publish()
            val marks = runBlocking { store.openRecordContent(a)!! }.items.filterIsInstance<ContentItem.Photo>().single().strokes
            assertEquals(1, marks.size)
            assertTrue(marks.single().points.any { kotlin.math.abs(it.x - 0.28f) < 0.025f && kotlin.math.abs(it.y - 0.63f) < 0.025f })
            openViewer()
            click("放大")
            assertMarker(viewer, blue, marked = true, zoom = 1.5f)
            click("查看原图")
            assertMarker(viewer, blue, marked = false, zoom = 1.5f)
            click("查看标注")
            assertMarker(viewer, blue, marked = true, zoom = 1.5f)
            click("关闭照片")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)

            click("删除", scroll = true)
            click("确认删除")
            awaitNode(hasTestTag("search"))
            click("返回")
            click("回收站")
            val trash = hasTestTag("trash-list")
            awaitNode(trash).performScrollToNode(hasText(main))
            compose.onNode(hasText(main) and hasAnyAncestor(trash), useUnmergedTree = true).performTouchInput { click() }
            click("彻底删除", scroll = true)
            click("确认彻底删除")
            awaitNode(hasText("已彻底删除"))
            click("返回")
            click("搜索全部记录")
            findRecord(fuse)
            openViewer()
            assertMarker(viewer, green, marked = true)
            assertMarker(viewer, blue, marked = false)
            click("查看原图")
            assertMarker(viewer, green, marked = false)
            assertArrayEquals(original, LocalPhotoOriginals.get(context).openOriginal(image).use { it.readBytes() })
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previousOrientation }
            erase(a)
            erase(b)
        }
    }

    @Test fun failedSaveKeepsInkForRetryAndCancelledEditsLeaveTheLastVersion() {
        val title = "圈画重试${System.nanoTime()}"
        val record = record(importPanel(), title)
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("folio.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            openSearch()
            findRecord(title)
            editPhoto()
            board.performTouchInput {
                down(0, center - Offset(40f, 0f))
                down(1, center + Offset(40f, 0f))
                updatePointerTo(0, center - Offset(60f, 0f))
                updatePointerTo(1, center + Offset(60f, 0f))
                move()
                up(0)
                up(1)
            }
            board.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 150%"))
            compose.onNodeWithText("撤销一笔").assertIsNotEnabled()
            touch(board, blue, zoom = 1.5f)
            val photo = runBlocking { store.openRecordContent(record)!! }.items.filterIsInstance<ContentItem.Photo>().single()
            db.execSQL("""CREATE TRIGGER reject_page_strokes BEFORE UPDATE OF photoStrokes ON draft_items
                WHEN NEW.itemId = '${photo.id.value}' BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
            click("保存标注")
            awaitNode(hasText("标注保存失败，请重试。"))
            assertMarker(board, blue, marked = true, zoom = 1.5f)
            val draft = runBlocking { store.editRecord(record) }
            fun draftPhoto() = runBlocking { store.openMixedDraft(draft)!! }.items.filterIsInstance<ContentItem.Photo>().single()
            assertTrue(draftPhoto().strokes.isEmpty())
            db.execSQL("DROP TRIGGER reject_page_strokes")
            click("重试保存")
            awaitNode(hasText("圈画照片"))
            assertEquals(1, draftPhoto().strokes.size)
            enterMarkup()
            click("清除标注")
            assertMarker(board, blue, marked = false)
            click("取消")
            enterMarkup()
            assertMarker(board, blue, marked = true)
            click("清除标注")
            click("保存标注")
            awaitNode(hasText("圈画照片"))
            assertTrue(draftPhoto().strokes.isEmpty())
            enterMarkup()
            touch(board, green)
            click("保存标注")
            click("放弃修改")
            click("确认放弃")
            openViewer()
            assertMarker(viewer, green, marked = false)
            compose.onNodeWithText("查看原图").assertDoesNotExist()
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS reject_page_strokes")
            db.close()
            erase(record)
        }
    }

    private fun importPanel(): PhotoId {
        val file = File.createTempFile("m04-panel", ".jpg", context.cacheDir)
        try {
            val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            val paint = Paint()
            Canvas(bmp).apply {
                drawColor(android.graphics.Color.GRAY)
                paint.color = android.graphics.Color.BLUE
                drawRect(70f, 112f, 98f, 140f, paint)
                paint.color = android.graphics.Color.GREEN
                drawRect(196f, 50f, 224f, 78f, paint)
            }
            file.outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bmp.recycle()
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_TRANSVERSE.toString())
                saveAttributes()
            }
            return runBlocking { LocalPhotoOriginals.get(context).importPhoto(Uri.fromFile(file)) }
        } finally { file.delete() }
    }

    private fun record(image: PhotoId, title: String) = runBlocking {
        val draft = store.appendPhotos(null, listOf(image))
        store.saveDraftTitle(draft, title)
        store.completeMixedDraft(draft)
    }

    private fun erase(id: RecordId) = runBlocking {
        store.moveToTrash(id)
        store.deletePermanently(id)
    }

    private fun openSearch() {
        compose.onNodeWithTag("nav-Records").performClick()
        click("搜索全部记录")
    }

    private fun findRecord(title: String) {
        awaitNode(hasTestTag("search")).performTextReplacement(title)
        val results = hasTestTag("search-results")
        awaitNode(results).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasAnyAncestor(results), useUnmergedTree = true).performTouchInput { click() }
        awaitNode(hasText("编辑"))
    }

    private fun editPhoto() { click("编辑"); enterMarkup() }
    private fun enterMarkup() {
        awaitNode(hasTestTag("mixed-editor")).performScrollToNode(hasText("圈画照片"))
        click("圈画照片")
        awaitNode(hasContentDescription("照片圈画画布"))
    }
    private fun publish() { click("完成"); awaitNode(hasText("编辑")) }
    private fun openViewer() {
        awaitNode(hasContentDescription("记录中的照片，点击放大")).performScrollTo().performClick()
        awaitNode(hasContentDescription("照片"))
    }

    private fun rotate(request: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(10_000) { context.resources.configuration.orientation == expected }
        compose.waitForIdle()
    }

    private fun target(width: Int, height: Int, point: Offset, zoom: Float): Offset {
        val scale = minOf(width / 200f, height / 300f) * zoom
        return Offset(width / 2f + (point.x - 0.5f) * 200 * scale, height / 2f + (point.y - 0.5f) * 300 * scale)
    }
    private fun touch(node: SemanticsNodeInteraction, point: Offset, zoom: Float = 1f, drag: Boolean = false) {
        node.performTouchInput {
            val p = target(width, height, point, zoom)
            if (drag) { down(p - Offset(6f, 0f)); moveTo(p); moveTo(p + Offset(6f, 0f)); up() } else click(p)
        }
    }
    private fun assertMarker(node: SemanticsNodeInteraction, point: Offset, marked: Boolean, zoom: Float = 1f) {
        val pixels = node.captureToImage().toPixelMap()
        val p = target(pixels.width, pixels.height, point, zoom)
        val colors = (-2..2).flatMap { dx -> (-2..2).map { dy -> pixels[p.x.toInt() + dx, p.y.toInt() + dy] } }
        val ink = colors.any { it.red > 0.65f && it.green < 0.55f && it.blue < 0.45f }
        assertEquals("标注位置 $point，缩放 $zoom", marked, ink)
        if (!marked) assertTrue(colors.any { if (point == blue) it.blue > 0.75f && it.red < 0.3f else it.green > 0.75f && it.red < 0.3f })
    }
    private fun click(text: String, scroll: Boolean = false) {
        val node = awaitNode(hasText(text) and hasClickAction() and isEnabled())
        if (scroll) node.performScrollTo()
        node.performClick()
    }
    private fun awaitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
}
