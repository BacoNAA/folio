package com.baconaa.folio

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentImportNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = LocalTextStore.get(context)
    private val folder = "Download/Folio-${System.nanoTime()}"
    private val sources = mutableListOf<Uri>()
    private var draftId: DraftId? = null
    private var recordId: RecordId? = null
    private val viewIntent = AtomicReference<Intent>()
    private var startAtRoot = false
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                // 从本用例的下载目录开始，实际选取、返回和授权均由系统文件选择器处理。
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", if (startAtRoot) "primary:" else "primary:$folder"))
            }
            if (intent.action == Intent.ACTION_VIEW && intent.type == "application/pdf") {
                viewIntent.set(Intent(intent))
                return Instrumentation.ActivityResult(0, null)
            }
            return null
        }
    }

    @Before fun monitorIntents() { instrumentation.addMonitor(monitor) }

    @After fun cleanUp() {
        instrumentation.removeMonitor(monitor)
        sources.forEach { context.contentResolver.delete(it, null, null) }
        runBlocking {
            draftId?.let { store.discardDraft(it) }
            recordId?.let { store.moveToTrash(it); store.deletePermanently(it) }
        }
    }

    @Test fun emptyDraftCanCancelThenPublishOnlyThePickedPdf() {
        val draftsBefore = runBlocking { store.listDrafts().map { it.id } }
        val pdf = sample("窗框凭证.pdf", "application/pdf")
        sample("bad.pdf", "application/pdf", "tone-660.mp3")
        compose.onNodeWithTag("write").performClick()
        choose("PDF 附件", after = 0, cancelling = true)
        cancelPicker()
        choose("PDF 附件")
        pickFile("bad.pdf")
        waitText("导入失败")
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("editor").assertExists()
        compose.onNodeWithText("完成").assertIsNotEnabled()
        assertEquals(draftsBefore, runBlocking { store.listDrafts().map { it.id } })
        choose("PDF 附件", after = 0)
        pickFile("窗框凭证.pdf")
        waitForEditorSave()
        draftId = runBlocking { store.listDrafts().single { it.id !in draftsBefore }.id }
        compose.onNodeWithText("返回").performClick()
        resumeDraft()
        compose.onNodeWithText("窗框凭证.pdf").assertIsDisplayed()
        openPdf()
        publish()
        assertEquals(1, runBlocking { store.openRecordContent(recordId!!) }!!.items.size)
        assertEquals(1, context.contentResolver.delete(pdf, null, null))
        openPdf()
    }

    @Test fun failedSelectionRetriesAfterPhotoAndRestoredRecordPlaysImportedAudio() {
        val text = "窗框安装${System.nanoTime()}"
        val pdf = sample("窗框凭证.pdf", "application/pdf")
        val mp3 = sample("tone-660.mp3", "audio/mpeg")
        sample("bad.mp3", "audio/mpeg", "窗框凭证.pdf")
        val id = runBlocking {
            val photo = LocalPhotoOriginals.get(context).importPhoto(Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
            requireNotNull(store.saveDraft(null, TextEdit(text, 2))).also {
                store.insertPhoto(it, null, photo)
                store.insertChecklist(it, null, listOf(ChecklistEntry(ChecklistEntryId.create(), "检查门锁")))
            }
        }
        draftId = id
        resumeDraft()
        val before = runBlocking { store.openMixedDraft(id) }!!.items
        choose("导入已有录音", after = 1)
        pickFile("bad.mp3")
        waitText("导入失败")
        compose.onNodeWithText("bad.mp3", substring = true).assertIsDisplayed()
        assertEquals(before, runBlocking { store.openMixedDraft(id) }!!.items)
        compose.onNodeWithText("重新选择").performClick()
        pickFile("tone-660.mp3")
        waitForEditorSave()
        val audio = runBlocking { store.openMixedDraft(id) }!!.items[2] as ContentItem.Audio
        choose("导入已有录音", after = 1, cancelling = true)
        cancelPicker()
        compose.onNodeWithTag("item-menu-1").assertIsDisplayed()
        choose("PDF 附件")
        pickFile("窗框凭证.pdf")
        waitForEditorSave()
        compose.onNodeWithText("返回").performClick()
        resumeDraft()
        val items = runBlocking { store.openMixedDraft(id) }!!.items
        assertEquals(listOf(before[0], before[1], audio, before[2]), items.dropLast(1))
        assertEquals("窗框凭证.pdf", (items.last() as ContentItem.Pdf).displayName)
        editorScroll(hasText("试听"))
        play(audio)
        editorScroll(hasText("打开 PDF"))
        openPdf()
        publish()
        assertEquals(1, context.contentResolver.delete(pdf, null, null))
        assertEquals(1, context.contentResolver.delete(mp3, null, null))
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitText("已移入回收站")
        compose.onNodeWithTag("nav-Records").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithTag("trash-list").performScrollToNode(hasText(text, substring = true))
        // 回收站卡片里的照片可单独放大，点正文进入记录详情。
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).performScrollTo().performClick()
        waitText("恢复记录")
        compose.onNodeWithText("恢复记录").performScrollTo().performClick()
        waitText("查看恢复记录")
        compose.onNodeWithText("查看恢复记录").performClick()
        waitText("打开 PDF")
        // 正文照片解码后会占据实际高度，再向下滚动到附件。
        compose.waitUntil(12_000) {
            compose.onAllNodesWithContentDescription("记录中的照片，点击放大").fetchSemanticsNodes().isNotEmpty()
        }
        openPdf()
        play(audio)
    }

    @Test fun pickedM4aPlaysFromTheReopenedRecordAfterDeletingTheSource() {
        val before = runBlocking { store.listDrafts().map { it.id } }
        val source = sample("tone-440.m4a", "audio/mp4")
        compose.onNodeWithTag("write").performClick()
        choose("导入已有录音")
        pickFile("tone-440.m4a")
        waitForEditorSave()
        draftId = runBlocking { store.listDrafts().single { it.id !in before }.id }
        val audio = runBlocking { store.openMixedDraft(draftId!!) }!!.items.single() as ContentItem.Audio
        publish("试听")
        assertEquals(1, context.contentResolver.delete(source, null, null))
        play(audio)
    }

    @Test fun pickedVideoPlaysAndPausesFromTheSamePublishedRecord() {
        val arguments = InstrumentationRegistry.getArguments()
        val resume = arguments.getString("m02ResumeRecord")?.let(::RecordId)
        val body: String
        if (resume == null) {
            body = "M02短片验收${System.nanoTime()}"
            val draft = runBlocking { requireNotNull(store.saveDraft(null, TextEdit(body, 0))) }
            draftId = draft
            val valid = sample("短片.mp4", "video/mp4", "short-avc-aac.mp4", "m02")
            sample("无画面.mp4", "video/mp4", "tone-440.m4a")
            resumeDraft()
            choose("导入视频", after = 0)
            pickFile("无画面.mp4")
            waitText("导入失败")
            compose.onNodeWithText("格式", substring = true).assertIsDisplayed()
            compose.onNodeWithText("重新选择").performClick()
            pickFile("短片.mp4")
            waitForEditorSave()
            publish("播放视频")
            val saved = runBlocking { store.openRecordContent(requireNotNull(recordId))!! }
            assertEquals(body, (saved.items.first() as ContentItem.Text).text)
            assertTrue(saved.items.size == 2 && saved.items.last() is ContentItem.Video)
            assertEquals(1, context.contentResolver.delete(valid, null, null))
            if (arguments.getString("m02PrepareColdStart") == "true") {
                // 交给下一次进程从正常入口打开；常规运行在本方法内播放并清理样本。
                instrumentation.sendStatus(0, Bundle().apply { putString("m02SavedId", requireNotNull(recordId).value) })
                recordId = null
                return
            }
        } else {
            val saved = runBlocking { requireNotNull(store.openRecordContent(resume)) }
            body = (saved.items.first() as ContentItem.Text).text
            check(body.startsWith("M02短片验收") && (saved.items.last() as ContentItem.Video).displayName == "短片.mp4")
            recordId = resume
            compose.onNodeWithTag("nav-Records").performClick()
            compose.onNodeWithText("最近记录").performClick()
            waitText(body)
            compose.onNodeWithTag("records-list").performScrollToNode(hasText(body))
            compose.onNode(hasText(body) and hasAnyAncestor(hasTestTag("records-list")), useUnmergedTree = true)
                .performTouchInput { click() }
            waitText("播放视频")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("视频预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("播放视频").performScrollTo().performClick()
        val pause = hasText("暂停视频") and isEnabled()
        compose.waitUntil(10_000) { compose.onAllNodes(pause).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(pause).performClick()
        compose.onNodeWithText("继续播放").assertIsEnabled().performClick()
        compose.onNodeWithText("关闭视频").performClick()
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText(body).performScrollTo().assertIsDisplayed()
    }

    @Test fun movedVideoAndContinuedTextSurviveLeavingTheEditor() {
        val source = sample("续写.mp4", "video/mp4", "short-avc-aac.mp4", "m02")
        val draft = runBlocking { requireNotNull(store.saveDraft(null, TextEdit("看短片", 3))) }
        draftId = draft
        val video = runBlocking { store.importMedia(source, ImportType.VIDEO, draft).item }
        resumeDraft()
        editorScroll(hasTestTag("item-menu-1"))
        compose.onNodeWithTag("item-menu-1").performClick()
        compose.onNodeWithText("上移一项").performClick()
        editorScroll(hasTestTag("editor"))
        compose.onNodeWithTag("editor").performTextReplacement("看短片后继续写")
        compose.onNodeWithText("返回").performClick()
        resumeDraft()
        val items = runBlocking { requireNotNull(store.openMixedDraft(draft)).items }
        assertEquals(video, items.first())
        assertEquals("看短片后继续写", (items.last() as ContentItem.Text).text)
        editorScroll(hasText("播放视频"))
        compose.onNodeWithText("播放视频").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("暂停视频") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭视频").performClick()
        compose.onNodeWithText("记录这一刻").assertIsDisplayed()
    }

    private fun sample(name: String, mime: String, asset: String = name, assetFolder: String = "m01"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        sources += uri
        instrumentation.context.assets.open("$assetFolder/$asset").use { input ->
            resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun choose(label: String, after: Int? = null, cancelling: Boolean = false) {
        startAtRoot = cancelling
        val tag = after?.let { "item-menu-$it" } ?: "add-content"
        editorScroll(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        if (after != null) compose.onNodeWithText("在此后添加").performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun pickerRoot(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
        it.packageName?.toString()?.endsWith(".documentsui") == true
    }

    private fun pickFile(name: String) {
        val timeout = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < timeout) {
            val node = pickerRoot()?.findAccessibilityNodeInfosByText(name)?.firstOrNull { it.text?.toString() == name && it.isVisibleToUser }
            if (node != null) {
                instrumentation.uiAutomation.waitForIdle(400, 5_000)
                if (!node.refresh()) continue
                // DocumentsUI 的文件行由 RecyclerView 处理触摸，文本节点可以没有点击操作。
                val bounds = Rect().also(node::getBoundsInScreen)
                val command = "input tap ${bounds.centerX()} ${bounds.centerY()}"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                compose.waitUntil(12_000) { pickerRoot() == null }
                return
            }
            SystemClock.sleep(80)
        }
        val root = pickerRoot()
        val titles = root?.findAccessibilityNodeInfosByViewId("android:id/title")?.map { it.text?.toString() }
        fail("系统选择器未显示 $name，当前文件列表为 $titles")
    }

    private fun cancelPicker() {
        compose.waitUntil(12_000) { pickerRoot() != null }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(12_000) { pickerRoot() == null }
        waitText("记录这一刻")
    }

    private fun resumeDraft() {
        compose.waitUntil(12_000) { compose.onAllNodesWithTag("nav-Records").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-Records").performClick()
        val label = hasText("继续草稿", substring = true)
        compose.waitUntil(12_000) { compose.onAllNodes(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(label).performClick()
        if (runBlocking { store.listDrafts().size } > 1) compose.onNodeWithTag("draft-${draftId!!.value}").performClick()
        waitForEditorSave()
    }

    private fun publish(detailAction: String = "打开 PDF") {
        compose.onNodeWithText("完成").assertIsEnabled().performClick()
        waitText("已保存 · 查看")
        recordId = compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)[FolioViewModel::class.java].state.savedId) }
        draftId = null
        compose.onNodeWithText("已保存 · 查看").performScrollTo().performClick()
        waitText(detailAction)
    }

    private fun openPdf() {
        viewIntent.set(null)
        compose.onNodeWithText("打开 PDF").performScrollTo().performClick()
        compose.waitUntil(10_000) { viewIntent.get() != null }
        val view = viewIntent.get()
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags)
        assertEquals("${context.packageName}.pdf", view.data!!.authority)
        val bytes = context.contentResolver.openInputStream(view.data!!)!!.use { it.readBytes() }
        assertTrue(bytes.toString(Charsets.US_ASCII).contains("LATCH-4827"))
    }

    private fun play(audio: ContentItem.Audio) {
        val player = compose.runOnIdle { LocalAudioPlayback.get(context) }
        compose.onNodeWithText("试听").performScrollTo().performClick()
        compose.waitUntil(10_000) { player.state.value.let { it.audioId == audio.audioId && it.hasEnded && it.error == null } }
    }

    private fun editorScroll(matcher: SemanticsMatcher) = compose.onNodeWithTag("mixed-editor").performScrollToNode(matcher)

    private fun waitForEditorSave() {
        waitText("草稿已保存")
        compose.waitUntil(12_000) { compose.onAllNodes(hasText("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
}
