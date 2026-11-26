package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordMergeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)
    private val model get() = ViewModelProvider(compose.activity)[FolioViewModel::class.java]
    private val suffix = System.nanoTime().toString()
    private val group = RecordGroupId("展览$suffix")
    private val records = mutableListOf<RecordId>()
    private var draft: DraftId? = null

    @After fun clean() = runBlocking {
        draft?.let { store.discardDraft(it) }
        store.deleteGroup(group)
        records.forEach { store.moveToTrash(it); store.deletePermanently(it) }
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 12_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher)
    }

    private fun click(label: String) = node(hasText(label) and hasClickAction() and isEnabled()).performClick()

    private fun listItem(list: String, tag: String): SemanticsNodeInteraction {
        node(hasTestTag(list)).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private suspend fun audioSource(): RecordId {
        val cameraCache = File(context.cacheDir, "camera").also { it.mkdirs() }
        val file = File.createTempFile("exhibit-", ".m4a", cameraCache)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("m01/tone-440.m4a").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            val audioDraft = store.importMedia(uri, ImportType.AUDIO).draftId
            store.saveDraftTitle(audioDraft, "看展录音$suffix")
            return store.completeMixedDraft(audioDraft).also { records.add(it) }
        } finally { file.delete() }
    }

    private suspend fun photoSource(): RecordId {
        val uri = Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}")
        val photo = LocalPhotoOriginals.get(context).importPhoto(uri)
        val photoDraft = store.appendPhotos(null, listOf(photo))
        store.saveDraftTitle(photoDraft, "展馆照片$suffix")
        return store.completeMixedDraft(photoDraft).also { records.add(it) }
    }

    @Test fun changedSourceRequiresANewPreviewBeforeCreatingTheDraft() {
        suspend fun textSource(text: String): RecordId {
            val id = requireNotNull(store.saveDraft(null, TextEdit(text, 0)))
            return store.completeMixedDraft(id).also { records.add(it) }
        }
        val first = runBlocking {
            val id = requireNotNull(store.saveDraft(null, TextEdit("旧展签$suffix", 0)))
            store.createDraftTag(id, "看展$suffix")
            store.completeMixedDraft(id).also { records.add(it) }
        }
        val second = runBlocking { textSource("纸质门票$suffix") }
        val previousDrafts = runBlocking { store.listDrafts().map { it.id }.toSet() }
        compose.onNodeWithTag("nav-Records").performClick()
        click("多选合并")
        for (source in listOf(first, second)) listItem("merge-sources", "merge-source-${source.value}").performClick()
        click("预览合并")
        node(hasTestTag("merge-options"))
        runBlocking {
            val edit = store.editRecord(first)
            store.completeDraft(edit, TextEdit("新版展签$suffix", 0))
        }
        click("创建合并草稿")
        node(hasText("来源已发生变化，请重新预览后再创建。"))
        assertEquals(previousDrafts, runBlocking { store.listDrafts().map { it.id }.toSet() })
        click("预览合并")
        val item = runBlocking { store.openRecordContent(first)!!.items.single().id }
        listItem("merge-options", "merge-preview-${item.value}").assertTextContains("新版展签$suffix", substring = true)
        listItem("merge-options", "merge-remove-${item.value}").performClick()
        node(hasText("创建合并草稿") and hasClickAction()).assertIsNotEnabled()
        val addBack = hasText("加回 ·", substring = true) and hasClickAction()
        node(hasTestTag("merge-options")).performScrollToNode(addBack)
        compose.onNode(addBack).performClick()
        val tag = hasText("标签 · 看展$suffix") and hasClickAction()
        node(hasTestTag("merge-options")).performScrollToNode(tag)
        compose.onNode(tag).performClick()
        click("创建合并草稿")
        node(hasTestTag("mixed-editor"))
        draft = compose.runOnIdle { requireNotNull(model.state.editor?.draftId) }
        assertEquals(previousDrafts + requireNotNull(draft), runBlocking { store.listDrafts().map { it.id }.toSet() })
        assertEquals(listOf("看展$suffix"), runBlocking { requireNotNull(store.openDraftTags(requireNotNull(draft))).map { it.name } })
    }

    @Test fun cancelThenMergePhotoBeforeAudioAndPlayFromTheNewDetail() {
        val sound = runBlocking { audioSource() }
        val picture = runBlocking { photoSource() }
        val sourceOrder = listOf(sound, picture)
        val before = runBlocking {
            store.createGroup(group, "展览$suffix", sourceOrder)
            sourceOrder.map { requireNotNull(store.openRecordContent(it)) }
        }
        val photo = before.last().items.single() as ContentItem.Photo
        val audio = before.first().items.single() as ContentItem.Audio
        val previousDrafts = runBlocking { store.listDrafts().map { it.id }.toSet() }
        fun openAndReorder() {
            click("多选合并")
            for (source in sourceOrder) listItem("merge-sources", "merge-source-${source.value}").performClick()
            click("预览合并")
            listItem("merge-options", "merge-up-${photo.id.value}").performClick()
            listItem("merge-options", "merge-up-${photo.id.value}").assertIsNotEnabled()
        }
        compose.onNodeWithTag("nav-Records").performClick()
        openAndReorder()
        click("取消")
        assertEquals(previousDrafts, runBlocking { store.listDrafts().map { it.id }.toSet() })
        assertEquals(before, runBlocking { sourceOrder.map { store.openRecordContent(it) } })

        openAndReorder()
        click("创建合并草稿")
        node(hasTestTag("mixed-editor"))
        val mergedDraft = compose.runOnIdle { requireNotNull(model.state.editor?.draftId) }
        draft = mergedDraft
        click("完成")
        node(hasText("已保存 · 查看"))
        val merged = compose.runOnIdle { requireNotNull(model.state.savedId) }
        records.add(merged)
        runBlocking {
            assertFalse(merged in sourceOrder)
            assertEquals(merged, store.completeMixedDraft(mergedDraft))
            val result = requireNotNull(store.openRecordContent(merged)).items
            assertEquals(2, result.size)
            assertEquals(photo.photoId, (result[0] as ContentItem.Photo).photoId)
            assertEquals(audio.audioId, (result[1] as ContentItem.Audio).audioId)
            assertEquals(before, sourceOrder.map { store.openRecordContent(it) })
            assertEquals(sourceOrder, store.openGroup(group)!!.members.map { it.id })
        }
        node(hasText("已保存 · 查看") and hasClickAction()).performScrollTo().performClick()
        node(hasContentDescription("记录中的照片，点击放大")).performScrollTo().assertIsDisplayed()
        val playback = compose.runOnIdle { LocalAudioPlayback.get(context) }
        node(hasText("试听") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil("新详情中的看展录音播完", 12_000) {
            playback.state.value.let { it.audioId == audio.audioId && it.hasEnded && it.error == null }
        }
    }
}
