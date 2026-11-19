package com.baconaa.folio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.*
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MixedEditorStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = LocalTextStore.get(context)

    @Test
    fun fourTypesRestoreOrderUndoAndKeepFormalVersionUntilCompletion() {
        val source = photoSource()
        val photo = runBlocking { LocalPhotoOriginals.get(context).importPhoto(Uri.fromFile(source)) }
        val audio = createAudioTestClip(context)
        val original = "混合界面${System.nanoTime()}"
        val record = runBlocking {
            val draft = store.saveDraft(null, TextEdit(original, 2))!!
            store.insertPhoto(draft, null, photo)
            store.appendChecklist(draft, listOf(ChecklistEntry(ChecklistEntryId.create(), "带水")))
            store.appendAudio(draft, audio.id)
            store.completeMixedDraft(draft)
        }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store); model.openRecord(record) }
        await { model.state.detail != null && !model.state.busy }
        main { model.editRecord() }
        await { model.state.editor != null && !model.state.busy }
        val items = model.state.editor!!.items
        val textId = items[0].id
        val photoId = items[1].id
        val checklist = items[2] as ContentItem.Checklist
        val audioId = items[3].id
        main {
            model.changeText(textId, TextFieldValue("$original，续写", TextRange(2)))
            model.changeCaption(photoId, "新的照片说明")
            model.changeChecklist(checklist.id, checklist.entries.map { it.copy(checked = true) })
            model.changeCaption(audioId, "声音说明")
            model.move(audioId, MoveDirection.UP)
        }
        await { !model.state.busy && model.state.editor!!.items[2].id == audioId }
        main { model.remove(photoId) }
        await { !model.state.busy && model.state.editor!!.canUndo }
        val draftId = model.state.editor!!.draftId!!
        main { model.leaveEditor() }
        await { model.state.page == Page.Detail && !model.state.busy }
        assertEquals(original, runBlocking { store.openRecordContent(record) }!!.items.filterIsInstance<ContentItem.Text>().single().text)
        val preview = runBlocking { store.listDrafts() }.single { it.id == draftId }
        main { model = FolioViewModel(store, store, store, store); model.resume(preview) }
        await { model.state.editor != null && !model.state.busy }
        main { model.undo() }
        await { !model.state.busy && !model.state.editor!!.canUndo }
        main { model.complete() }
        await { model.state.page == Page.Detail && !model.state.busy }
        val published = runBlocking { store.openRecordContent(record) }!!
        assertEquals(listOf(textId, photoId, audioId, checklist.id), published.items.map { it.id })
        assertEquals("$original，续写", (published.items[0] as ContentItem.Text).text)
        assertEquals("新的照片说明", (published.items[1] as ContentItem.Photo).caption)
        assertEquals("声音说明", (published.items[2] as ContentItem.Audio).caption)
        assertTrue((published.items[3] as ContentItem.Checklist).entries.single().checked)
        source.delete()
        LocalPhotoOriginals.get(context).openOriginal(photo).use { assertTrue(it.read() >= 0) }
        runBlocking { store.moveToTrash(record); store.deletePermanently(record) }
    }

    @Test
    fun importedPhotoRetriesBindingWithoutReimportOrDuplicate() {
        var fail = true
        val failing = object : TextDrafts by store {
            override suspend fun appendPhotos(id: DraftId?, photos: List<PhotoId>, initialEventTime: EventTime?): DraftId {
                if (fail) throw IOException("binding failed")
                return store.appendPhotos(id, photos, initialEventTime)
            }
        }
        val source = photoSource()
        val media = TestMedia(context)
        lateinit var model: FolioViewModel
        main {
            model = FolioViewModel(failing, store, store, store, media)
            model.receivePhoto(Uri.fromFile(source), ExternalTarget(null, null, null, Page.Capture, Page.Records))
        }
        await { !model.state.busy && model.state.pendingAttachment != null }
        source.delete()
        main { fail = false; model.retryAttachment() }
        await { !model.state.busy && model.state.pendingAttachment == null }
        assertEquals(1, media.imports)
        assertEquals(1, model.state.editor!!.items.size)
        main { model.complete() }
        await { !model.state.busy && model.state.savedId != null }
        runBlocking { store.moveToTrash(model.state.savedId!!); store.deletePermanently(model.state.savedId!!) }
    }

    @Test
    fun failedPhotoImportCanBeAbandonedWithoutDiscardingTheTextDraft() {
        lateinit var model: FolioViewModel
        main {
            model = FolioViewModel(store, store, store, store, TestMedia(context))
            model.newText()
            model.change(TextFieldValue("照片来源失效时保留文字"))
        }
        await { model.state.editor?.draftId != null && model.state.editor!!.savedRevision == model.state.editor!!.revision }
        val id = model.state.editor!!.draftId!!
        main {
            model.receivePhoto(Uri.fromFile(File(context.cacheDir, "missing-${System.nanoTime()}.png")),
                ExternalTarget(id.value, null, null, Page.Capture, Page.Records))
        }
        await { !model.state.busy && model.state.importFailed }
        main { model.cancelPhotoImport(); model.change(TextFieldValue("原草稿可以继续编辑")); model.complete() }
        await { !model.state.busy && model.state.savedId != null }
        val record = model.state.savedId!!
        assertEquals("原草稿可以继续编辑", (runBlocking { store.openRecordContent(record) }!!.items.single() as ContentItem.Text).text)
        runBlocking { store.moveToTrash(record); store.deletePermanently(record) }
    }

    @Test
    fun readFailureAfterFirstSaveOrInsertionDoesNotDuplicateOrLoseLatestInput() {
        var failRead = true
        val failing = object : MixedContentDrafts by store {
            override suspend fun openMixedDraft(id: DraftId): MixedDraft? {
                if (failRead) throw IOException("read failure")
                return store.openMixedDraft(id)
            }
        }
        lateinit var model: FolioViewModel
        val text = "首次落盘${System.nanoTime()}"
        main { model = FolioViewModel(store, store, failing, store); model.newText(); model.change(TextFieldValue(text)) }
        await { model.state.saveError != null }
        main { model.change(TextFieldValue("$text，最后输入")); failRead = false; model.save() }
        await { model.state.editor?.draftId != null && model.state.saveError == null && model.state.editor!!.revision == model.state.editor!!.savedRevision }
        val id = model.state.editor!!.draftId!!
        assertEquals("$text，最后输入", runBlocking { store.openDraft(id) }!!.text)
        assertEquals(1, runBlocking { store.listDrafts() }.count { it.excerpt.startsWith(text) })
        main { failRead = true; model.addChecklist() }
        await { !model.state.busy && model.state.editor!!.needsReload }
        main { failRead = false; model.reload() }
        await { !model.state.busy && !model.state.editor!!.needsReload }
        assertEquals(2, model.state.editor!!.items.size)
        main { model.discard() }
        await { !model.state.busy && model.state.editor == null }
    }

    @Test
    fun leavingForegroundStopsRecordingAndBindsClipToTheOriginalInsertionPosition() {
        val media = TestMedia(context).apply { clip = createAudioTestClip(context) }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store, media); model.newText(); model.change(TextFieldValue("录音前的文字")) }
        await { model.state.editor?.draftId != null && model.state.editor!!.savedRevision == model.state.editor!!.revision }
        val editor = model.state.editor!!
        main { model.startRecording(ExternalTarget(editor.draftId!!.value, null, editor.items.single().id.value, Page.Capture, Page.Records)) }
        await { model.state.recording && !model.state.busy }
        main { model.background() }
        await { !model.state.recording && !model.state.busy && model.state.editor!!.items.size == 2 }
        assertTrue(model.state.editor!!.items[1] is ContentItem.Audio)
        assertEquals(1, media.stops)
        main { model.discard() }
        await { !model.state.busy && model.state.editor == null }
    }

    @Test
    fun titleAloneCanResumeAndFirstTextUsesTheSameDraft() {
        val title = "标题先行${System.nanoTime()}"
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store); model.newText(); model.changeTitle(title); model.leaveEditor() }
        await { model.state.editor == null && !model.state.busy }
        val preview = runBlocking { store.listDrafts().single { it.title == title } }
        assertNull(runBlocking { store.openDraftTimes(preview.id) }!!.firstRecordedAt)
        assertTrue(runBlocking { store.openMixedDraft(preview.id) }!!.items.isEmpty())
        main { model = FolioViewModel(store, store, store, store); model.resume(preview) }
        await { model.state.editor?.title == title && !model.state.busy }
        main { model.complete() }
        assertFalse(model.state.editor!!.hasContent)
        main { model.change(TextFieldValue("第一项实际内容")); model.complete() }
        await { model.state.savedId != null && !model.state.busy }
        val id = model.state.savedId!!
        val record = runBlocking { store.openRecordContent(id) }!!
        assertEquals(title, record.title)
        assertEquals("第一项实际内容", (record.items.single() as ContentItem.Text).text)
        assertEquals(id, runBlocking { store.queryRecords(title).single().id })
        runBlocking { store.moveToTrash(id); store.deletePermanently(id) }
    }

    @Test
    fun importingPhotoOnlyChangesEventTimeWhenAccepted() {
        val source = photoSource()
        val capture = PhotoCaptureTime(java.time.LocalDateTime.parse("2012-01-02T03:04:05"), java.time.ZoneOffset.ofHours(9))
        val media = TestMedia(context).apply { this.capture = capture }
        lateinit var model: FolioViewModel
        main { model = FolioViewModel(store, store, store, store, media); model.newText(); model.changeTitle("旧照片") }
        await { model.state.editor?.draftId != null }
        val id = model.state.editor!!.draftId!!
        val original = model.state.editor!!.eventTime
        // 系统选图回调在启动操作结束后返回。
        fun picked() = main { model.receivePhoto(Uri.fromFile(source), ExternalTarget(id.value, null, null,
            Page.Capture, Page.Records, suggestPhotoTime = true)) }
        picked(); await { !model.state.busy && model.state.editor?.photoTimeOffer != null }
        main { model.resolvePhotoTime(false) }
        assertEquals(original, runBlocking { store.openDraftTimes(id) }!!.eventTime)
        picked(); await { !model.state.busy && model.state.editor?.photoTimeOffer != null }
        main { model.resolvePhotoTime(true); model.complete() }
        await { model.state.savedId != null && !model.state.busy }
        val recordId = model.state.savedId!!
        val record = runBlocking { store.openRecordContent(recordId) }!!
        assertEquals(EventTime(capture.localDateTime.toLocalDate(), capture.localDateTime.toLocalTime(), capture.offset!!), record.times.eventTime)
        assertEquals(2, record.items.filterIsInstance<ContentItem.Photo>().size)
        assertEquals(2, record.items.size)
        runBlocking { store.moveToTrash(recordId); store.deletePermanently(recordId) }
        source.delete()
    }

    private fun photoSource(): File {
        val file = File.createTempFile("ui-source", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("UI state did not settle")
    }
    private class TestMedia(private val context: Context) : EditorMedia {
        override val recording = MutableStateFlow(AudioRecordingState())
        override val playback = MutableStateFlow(AudioPlaybackState())
        lateinit var clip: AudioClip
        var capture: PhotoCaptureTime? = null
        override suspend fun readCaptureTime(id: PhotoId) = capture
        var imports = 0
        var stops = 0
        override suspend fun importPhoto(uri: Uri): PhotoId { imports++; return LocalPhotoOriginals.get(context).importPhoto(uri) }
        override fun start() { recording.value = AudioRecordingState(isRecording = true) }
        override fun stop(): AudioClip { stops++; recording.value = AudioRecordingState(); return clip }
        override fun cancel() { recording.value = AudioRecordingState() }
        override fun play(id: AudioId) { playback.value = AudioPlaybackState(id, isPlaying = true) }
        override fun pause(id: AudioId) { playback.value = AudioPlaybackState(id) }
        override fun stopPlayback() { playback.value = AudioPlaybackState() }
        override fun release() {}
    }
}
