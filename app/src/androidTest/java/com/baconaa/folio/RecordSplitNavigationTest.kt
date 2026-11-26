package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordSplitNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)
    private val title = "山路游记${System.nanoTime()}"

    @After fun clean() = runBlocking {
        store.listDrafts().filter { it.title == title }.forEach { store.discardDraft(it.id) }
        store.search(title).forEach { store.moveToTrash(it.id); store.deletePermanently(it.id) }
    }

    private suspend fun sourceWithAudio(audioOnly: Boolean = false): RecordId {
        val draft = if (audioOnly) null else {
            val id = requireNotNull(store.saveDraft(null, TextEdit("山间步道", 0)))
            val photo = LocalPhotoOriginals.get(context).importPhoto(Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            store.insertPhoto(id, null, photo, "山顶")
            id
        }
        val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
        val file = File.createTempFile("hike-", ".m4a", dir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("m01/tone-440.m4a").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            val imported = store.importMedia(uri, ImportType.AUDIO, draft)
            store.saveDraftTitle(imported.draftId, title)
            return store.completeMixedDraft(imported.draftId)
        } finally { file.delete() }
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 12_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher)
    }

    private fun button(label: String) = node(hasText(label) and hasClickAction() and isEnabled())

    private fun option(tag: String, list: String = "split-options"): SemanticsNodeInteraction {
        node(hasTestTag(list)).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun openSourceFromRecords() {
        button("搜索全部记录").performClick()
        node(hasTestTag("search")).performTextReplacement(title)
        node(hasText("1 条记录"))
        node(hasTestTag("search-results")).performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("search-results")), useUnmergedTree = true)
            .performTouchInput { click() }
        node(hasText("拆分记录") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    private fun openSplit() = button("拆分记录").performScrollTo().performClick()

    @Test fun removePhotoFromSplitDraftThenResumeAndRetryMovingOnlyTheAudio() {
        val source = runBlocking { sourceWithAudio() }
        val before = runBlocking { store.openRecordContent(source)!! }
        val (text, photo, sound) = before.items
        val audio = sound as ContentItem.Audio
        compose.onNodeWithTag("nav-Records").performClick()
        openSourceFromRecords()
        openSplit()
        option("split-item-${photo.id.value}").performClick()
        option("split-item-${sound.id.value}").performClick()
        option("split-remain-${text.id.value}").assertTextContains("山间步道", substring = true)
        option("split-preview-${sound.id.value}").assertTextContains("2. 录音", substring = true)
        button("创建拆分草稿").performClick()
        node(hasText("拆分草稿"))
        val id = runBlocking { store.listDrafts().single { it.splitSource == source }.id }
        option("item-menu-0", "mixed-editor").performClick()
        button("移除这一项").performClick()
        node(hasText("撤销移除"))

        val db = Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db").build()
        try {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""CREATE TRIGGER fail_split_ui BEFORE INSERT ON text_records
                WHEN NEW.sourceDraftId = '${id.value}' BEGIN SELECT RAISE(ABORT, 'fail_split_ui'); END""")
            button("完成").performClick()
            node(hasText("完成失败，草稿仍保留，请重试。"))
            assertEquals(before, runBlocking { store.openRecordContent(source) })
            button("返回").performClick()
            openSourceFromRecords()
            val player = compose.runOnIdle { LocalAudioPlayback.get(context) }
            button("试听").performScrollTo().performClick()
            compose.waitUntil("原记录中的录音播完", 10_000) {
                player.state.value.let { it.audioId == audio.audioId && it.hasEnded && it.error == null }
            }
            button("继续拆分草稿").performScrollTo().performClick()
            node(hasText("拆分草稿"))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_split_ui")
            db.close()
        }
        button("完成").performClick()
        button("已保存 · 查看").performScrollTo().performClick()
        button("试听").performScrollTo().assertIsDisplayed()
        runBlocking {
            val result = store.search(title).single { it.id != source }.id
            assertEquals(result, store.completeMixedDraft(id))
            assertEquals(audio.audioId, (store.openRecordContent(result)!!.items.single() as ContentItem.Audio).audioId)
            val remaining = store.openRecordContent(source)!!
            assertEquals(listOf(text, photo), remaining.items)
            assertEquals(before.times.firstRecordedAt, remaining.times.firstRecordedAt)
        }
    }

    @Test fun existingEditAndSplitEachKeepTheirDraftUntilExplicitlyDiscarded() {
        val source = runBlocking { sourceWithAudio() }
        val original = runBlocking { store.openRecordContent(source)!! }
        val first = original.items.first()
        val pending = runBlocking {
            store.editRecord(source).also { store.editTextItem(it, first.id, TextEdit("新增路线", 0)) }
        }
        compose.onNodeWithTag("nav-Records").performClick()
        openSourceFromRecords()
        compose.onNode(hasText("拆分记录") and hasClickAction()).assertIsNotEnabled()
        assertEquals("新增路线", runBlocking { (store.openMixedDraft(pending)!!.items.first() as ContentItem.Text).text })
        button("继续未完成修改").performScrollTo().performClick()
        button("放弃修改").performClick()
        button("确认放弃").performClick()
        openSplit()
        option("split-item-${first.id.value}").performClick()
        button("创建拆分草稿").performClick()
        node(hasText("拆分草稿"))
        button("返回").performClick()
        openSourceFromRecords()
        button("编辑").performClick()
        node(hasText(requireNotNull(SplitDraftConflict().message)))
        button("继续拆分草稿").performScrollTo().performClick()
        button("放弃修改").performClick()
        button("确认放弃").performClick()
        button("拆分记录").performScrollTo().assertIsDisplayed()
        assertEquals(original, runBlocking { store.openRecordContent(source) })
        assertTrue(runBlocking { store.listDrafts().none { it.splitSource == source || it.recordId == source } })
    }

    @Test fun onlyAudioCanBeDeselectedOrCopiedWithoutEmptyingItsSource() {
        val source = runBlocking { sourceWithAudio(audioOnly = true) }
        val before = runBlocking { store.openRecordContent(source)!! }
        val audio = before.items.single()
        compose.onNodeWithTag("nav-Records").performClick()
        openSourceFromRecords()
        openSplit()
        option("split-item-${audio.id.value}").performClick()
        node(hasText("创建拆分草稿") and hasClickAction()).assertIsNotEnabled()
        button("转为复制").assertIsDisplayed()
        option("split-item-${audio.id.value}").performClick()
        option("split-remain-${audio.id.value}").assertTextContains("录音", substring = true)
        compose.onNodeWithText("转为复制").assertDoesNotExist()
        option("split-item-${audio.id.value}").performClick()
        button("转为复制").performClick()
        option("copy-item-${audio.id.value}", "copy-options").assertIsOn()
        button("创建副本草稿").performClick()
        node(hasText("记录这一刻"))
        button("完成").performClick()
        button("已保存 · 查看")
        assertEquals(before, runBlocking { store.openRecordContent(source) })
        assertEquals(2, runBlocking { store.search(title).size })
    }
}
