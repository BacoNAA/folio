package com.baconaa.folio.records

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.createAudioTestClip
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baconaa.folio.MainActivity
import org.junit.Rule
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class D01UpgradeProbe {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = context.getSharedPreferences("m01-upgrade-probe", 0)
    private val store = LocalTextStore.get(context)
    private val photos = LocalPhotoOriginals.get(context)
    private val sounds = LocalAudioOriginals.get(context)

    private fun sha(input: InputStream) = input.use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }

    private fun checkVersion(expected: Int) {
        SQLiteDatabase.openDatabase(context.getDatabasePath("folio.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(expected, it.version) }
    }

    @Test fun seed() = runBlocking {
        check(store.listDrafts().isEmpty() && store.listRecentRecords().isEmpty() && store.listTrash().isEmpty())
        checkVersion(17)
        check(!saved.contains("trip"))
        val file = File(context.cacheDir, "upgrade-map.png")
        val bitmap = Bitmap.createBitmap(10, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val photo = photos.importPhoto(Uri.fromFile(file))
        check(file.delete())
        val audio = createAudioTestClip(context, 1200)
        val tripDraft = store.saveDraft(null, TextEdit("秋游", 2))!!
        store.appendPhotos(tripDraft, listOf(photo))
        store.appendAudio(tripDraft, audio.id)
        val trip = store.completeMixedDraft(tripDraft)
        store.setStarred(trip, true)
        val edit = store.editRecord(trip)
        store.insertText(edit, null, TextEdit("补记山路", 4))
        val rain = store.saveDraft(null, TextEdit("明天带伞", 4))!!
        val ticket = store.completeMixedDraft(store.saveDraft(null, TextEdit("旧车票", 3))!!)
        val hidden = store.editRecord(ticket)
        store.insertText(hidden, null, TextEdit("票面铅笔字", 5))
        assertTrue(store.moveToTrash(ticket))
        val times = store.openRecordContent(trip)!!.times
        check(saved.edit().putString("trip", trip.value).putString("edit", edit.value).putString("rain", rain.value)
            .putString("ticket", ticket.value).putString("hidden", hidden.value)
            .putString("photoSha", sha(photos.openOriginal(photo))).putString("audioSha", sha(sounds.openOriginal(audio.id)))
            .putLong("first", times.firstRecordedAt).putLong("modified", times.modifiedAt).commit())
        checkVersion(17)
        verifyRetainedData()
    }

    private suspend fun verifyRetainedData() {
        val trip = RecordId(saved.getString("trip", null)!!)
        val content = store.openRecordContent(trip)!!
        assertTrue(content.marks.isStarred)
        assertEquals(listOf(trip), store.search("秋游").map { it.id })
        assertTrue(store.search("补记山路").isEmpty())
        assertEquals(saved.getLong("first", -1), content.times.firstRecordedAt)
        assertEquals(saved.getLong("modified", -1), content.times.modifiedAt)
        assertEquals(saved.getString("photoSha", null), sha(photos.openOriginal(content.photos.single())))
        val audio = content.items.last() as ContentItem.Audio
        assertEquals(saved.getString("audioSha", null), sha(sounds.openOriginal(audio.audioId)))
        assertEquals(audio.durationMillis, sounds.inspect(audio.audioId).durationMillis)
        val edit = DraftId(saved.getString("edit", null)!!)
        val rain = DraftId(saved.getString("rain", null)!!)
        assertEquals(setOf(edit,rain), store.listDrafts().map { it.id }.toSet())
        assertEquals("秋游\n补记山路", store.openDraft(edit)!!.text)
        assertEquals("明天带伞", store.openDraft(rain)!!.text)
        val ticket = RecordId(saved.getString("ticket", null)!!)
        assertEquals(listOf(ticket), store.listTrash().map { it.id })
        assertTrue(store.openTrashRecord(ticket)!!.hasDraft)
        assertNull(store.openMixedDraft(DraftId(saved.getString("hidden",null)!!)))
    }

    private fun checkTargetVersion() {
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedRoomVersion")) {
            "请通过 expectedRoomVersion 指定目标 Room 版本"
        }.toInt()
        checkVersion(expected)
    }

    @Test fun verifyAfterUpdate() {
        check(saved.contains("trip")) { "请先在首版专用安装运行 seed，或恢复升级前的样本" }
        runBlocking { verifyRetainedData() }
        checkTargetVersion()

        compose.onNodeWithTag("nav-Records").performClick()
        click("搜索全部记录")
        val search = compose.onNodeWithTag("search")
        search.performTextReplacement("补记山路")
        node(hasText("没有同时符合这些条件的记录", substring = true)).assertExists()
        search.performTextReplacement("秋游")
        val results = hasTestTag("search-results")
        val tripText = hasText("秋游") and hasAnyAncestor(results)
        node(tripText)
        compose.onNode(results).performScrollToNode(hasText("秋游"))
        // 卡片内的照片与播放器有各自的点击区域，从正文进入正式详情。
        compose.onNode(tripText, useUnmergedTree = true).performTouchInput { click() }
        node(action("编辑"))
        node(hasContentDescription("记录中的照片，点击放大")).performScrollTo().performClick()
        node(hasText("关闭照片") or hasContentDescription("关闭照片")).performClick()
        node(action("试听")).performScrollTo().performClick()
        node(action("暂停")).assertExists()
        node(action("试听")).assertExists()
        click("继续未完成修改")
        node(hasText("补记山路") and hasSetTextAction()).assertIsEnabled()
        click("返回")
        node(action("编辑"))
        click("返回")
        node(hasTestTag("search"))
        click("返回")

        click("继续草稿 · 2 份")
        node(hasText("明天带伞", substring = true) and hasClickAction()).performClick()
        node(hasText("明天带伞") and hasSetTextAction()).assertIsEnabled()
        click("返回")
        click("回收站")
        node(hasText("旧车票") and hasClickAction() and hasAnyAncestor(hasTestTag("trash-list"))).performClick()
        node(action("恢复记录"))
        compose.onNodeWithText("票面铅笔字", substring = true).assertDoesNotExist()
        click("恢复记录")
        click("查看恢复记录")
        click("继续未完成修改")
        node(hasText("票面铅笔字") and hasSetTextAction()).performTextReplacement("票面铅笔字仍可续写")
        click("返回")
        node(action("编辑"))
        val hidden = DraftId(requireNotNull(saved.getString("hidden", null)))
        runBlocking { assertEquals("旧车票\n票面铅笔字仍可续写", store.openDraft(hidden)!!.text) }
    }

    @Test fun blockUpgradeOnSeededFirstVersion() {
        check(saved.contains("trip")) { "请先在首版专用安装运行 seed" }
        checkVersion(17)
        SQLiteDatabase.openDatabase(context.getDatabasePath("folio.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Room 在迁移和校验之后登记新结构。在这里制造故障可以检查整条迁移链是否回滚。
            db.execSQL("""
                CREATE TRIGGER d01_block_upgrade BEFORE INSERT ON room_master_table
                WHEN NEW.identity_hash <> (SELECT identity_hash FROM room_master_table WHERE id = NEW.id)
                BEGIN SELECT RAISE(ABORT, 'D01 upgrade interrupted'); END
            """.trimIndent())
        }
    }

    @Test fun failedUpgradeShowsRetry() {
        openFailedLibrary()
        checkVersion(17)
    }

    @Test fun retryAfterFailedUpgrade() {
        openFailedLibrary()
        SQLiteDatabase.openDatabase(context.getDatabasePath("folio.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(17, db.version)
            db.rawQuery("SELECT name FROM pragma_table_info('record_items') WHERE name IN ('videoId', 'linkAddress')", null).use {
                assertFalse("失败的升级应回滚视频与网址字段", it.moveToFirst())
            }
            db.execSQL("DROP TRIGGER d01_block_upgrade")
        }
        click("重试")
        node(action("继续草稿 · 2 份"))
        runBlocking { verifyRetainedData() }
        checkTargetVersion()
    }

    private fun openFailedLibrary() {
        compose.onNodeWithTag("nav-Records").performClick()
        node(hasText("读取失败，请重试。")).assertIsDisplayed()
        compose.onNodeWithText("还没有记录", substring = true).assertDoesNotExist()
    }

    private fun action(text: String) = hasText(text) and hasClickAction() and isEnabled()

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    private fun click(text: String) = node(action(text)).performClick()
}
