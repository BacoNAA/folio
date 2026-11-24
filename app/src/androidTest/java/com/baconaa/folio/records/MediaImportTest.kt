package com.baconaa.folio.records

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.media.ImportedOriginals
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.LocalPhotoOriginals
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MediaImportTest : AudioStoreFixture() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sources = File(context.cacheDir, "camera/m01-${UUID.randomUUID()}")
    private val imported = mutableListOf<ContentItem>()

    private fun sample(name: String, folder: String = "m01"): File {
        check(sources.isDirectory || sources.mkdirs())
        return File(sources, name).also { file ->
            instrumentation.context.assets.open("$folder/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun source(file: File, name: String = file.name): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.camera", file, name)

    private suspend fun add(file: File, type: ImportType, draft: DraftId? = null, after: ItemId? = null): ImportedItem =
        store.importMedia(source(file), type, draft, after).also { imported += it.item }

    @After
    fun removeTestFiles() {
        sources.deleteRecursively()
        imported.forEach {
            when (it) {
                is ContentItem.Pdf -> File(context.filesDir, "pdf-originals/${it.pdfId.value}.pdf").delete()
                is ContentItem.Video -> File(context.filesDir, "video-originals/${it.videoId.value}.mp4").delete()
                is ContentItem.Audio -> audioOriginals.deleteOriginal(it.audioId)
                else -> Unit
            }
        }
    }

    @Test
    fun pdfAloneSurvivesReopenCompletionAndLossOfSourceWithReadOnlyAccess() = runBlocking {
        val file = sample("窗框凭证.pdf")
        val bytes = file.readBytes()
        val sourceFile = File(sources, "provider-id-731")
        check(file.renameTo(sourceFile))
        val selected = source(sourceFile, file.name)
        val pageBefore = renderedPage(selected)
        val result = store.importMedia(selected, ImportType.PDF)
        val pdf = (result.item as ContentItem.Pdf).also { imported += it }
        assertEquals(file.name, pdf.displayName)
        assertEquals(bytes.size.toLong(), pdf.sizeBytes)
        assertNotNull(store.openDraftTimes(result.draftId)!!.firstRecordedAt)
        reopenDatabase()
        assertEquals(result.draftId, store.listDrafts().single().id)
        assertEquals(listOf(pdf), store.openMixedDraft(result.draftId)!!.items)
        val record = store.completeMixedDraft(result.draftId)
        check(sourceFile.delete())
        reopenDatabase()
        assertEquals(record, store.completeMixedDraft(result.draftId))
        val content = store.openRecordContent(record)!!
        assertNull(content.title)
        assertEquals("", content.text)
        assertEquals(pdf, content.items.single())
        assertArrayEquals(bytes, store.openPdf(pdf.pdfId).use { it.readBytes() })
        val intent = store.pdfViewIntent(content.items.single() as ContentItem.Pdf)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val uri = requireNotNull(intent.data)
        assertEquals("content", uri.scheme)
        val resolver = context.contentResolver
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        assertArrayEquals(pageBefore, renderedPage(uri))
        assertTrue(runCatching { resolver.openFileDescriptor(uri, "rw")?.close() }.exceptionOrNull() is SecurityException)
        assertEquals(PreviewContentItem.Pdf(pdf.id, pdf.displayName), store.listRecentRecords().single().preview.items.single())
    }

    private fun renderedPage(uri: Uri): IntArray =
        PdfRenderer(context.contentResolver.openFileDescriptor(uri, "r")!!).use { document ->
            assertEquals(1, document.pageCount)
            document.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    IntArray(page.width * page.height).also {
                        bitmap.getPixels(it, 0, page.width, 0, 0, page.width, page.height)
                        assertTrue(it.any { pixel -> pixel != Color.WHITE })
                    }
                } finally { bitmap.recycle() }
            }
        }

    @Test
    fun searchingPublishedMediaUsesFullContentAndLastCompletedCaptions() = runBlocking {
        val file = sample("窗框凭证.pdf")
        val first = add(file, ImportType.PDF)
        val draft = first.draftId
        val text = store.insertText(draft, first.item.id, TextEdit("更换滤芯", 0))
        val audio = add(sample("tone-660.mp3"), ImportType.AUDIO, draft, text).item as ContentItem.Audio
        store.editAudioCaption(draft, audio.id, "第一次敲击")
        val video = add(sample("short-avc-aac.mp4", "m02"), ImportType.VIDEO, draft).item
        val record = store.completeMixedDraft(draft)
        add(file, ImportType.PDF)

        val card = store.queryRecords("窗框凭证", mediaTypes = setOf(MediaFilter.ATTACHMENT)).single()
        assertEquals(record, card.id)
        val detail = store.openRecordContent(record)!!.items
        assertEquals(listOf(first.item.id, text, audio.id, video.id), detail.map { it.id })
        assertEquals(detail.take(3).map { it.id }, card.preview.items.map { it.id })
        assertEquals(1, card.preview.remainingItemCount)
        assertEquals(card, store.listRecentRecords().single())
        assertEquals(listOf(record), store.search("窗框凭证").map { it.id })
        assertEquals(listOf(record), store.search("SHORT-AVC").map { it.id })
        assertEquals(card, store.queryRecords("SHORT-AVC", mediaTypes = setOf(MediaFilter.VIDEO)).single())
        assertTrue(store.queryRecords("LATCH-4827").isEmpty())

        val edit = store.editRecord(record)
        store.editAudioCaption(edit, audio.id, "修好后的声音")
        assertEquals(record, store.queryRecords("第一次敲击").single().id)
        assertTrue(store.search("修好后的声音").isEmpty())
        store.completeMixedDraft(edit)
        assertTrue(store.queryRecords("第一次敲击").isEmpty())
        assertEquals(record, store.search("修好后的声音").single().id)
        val recorded = publishAudio(audioClip().id)
        val pdfOnly = store.completeMixedDraft(add(file, ImportType.PDF).draftId)
        assertEquals(setOf(record, recorded), store.queryRecords(mediaTypes = setOf(MediaFilter.AUDIO)).map { it.id }.toSet())
        store.moveToTrash(record)
        assertEquals(listOf(pdfOnly), store.search("窗框凭证").map { it.id })
        assertTrue(store.queryRecords(mediaTypes = setOf(MediaFilter.VIDEO)).isEmpty())
    }

    @Test
    fun importedMp3KeepsItsPositionAndBothOriginalsSurviveTrashAndRestore() = runBlocking {
        val draft = requireNotNull(store.saveDraft(null, TextEdit("更换窗框", 4)))
        val text = store.openMixedDraft(draft)!!.items.single()
        val photo = store.insertPhoto(draft, null, importPhoto(Color.BLUE).id)
        val checklist = store.insertChecklist(draft, null, listOf(ChecklistEntry(ChecklistEntryId.create(), "核对收据")))
        val mp3File = sample("tone-660.mp3")
        val pdfFile = sample("窗框凭证.pdf")
        val mp3Bytes = mp3File.readBytes()
        val pdfBytes = pdfFile.readBytes()
        val audio = add(mp3File, ImportType.AUDIO, draft, photo).item as ContentItem.Audio
        val pdf = add(pdfFile, ImportType.PDF, draft).item as ContentItem.Pdf
        assertTrue(audio.durationMillis in 1_100..1_400)
        store.editAudioCaption(draft, audio.id, "检查声")
        reopenDatabase()
        val items = store.openMixedDraft(draft)!!.items
        assertEquals(listOf(text.id, photo, audio.id, checklist, pdf.id), items.map { it.id })
        assertEquals(audio.copy(caption = "检查声"), items[2])
        val record = store.completeMixedDraft(draft)
        check(mp3File.delete() && pdfFile.delete())
        val edit = store.editRecord(record)
        store.removeItem(edit, pdf.id)
        assertTrue(store.moveToTrash(record))
        reopenDatabase()
        assertNull(store.openMixedDraft(edit))
        assertEquals(items, store.openTrashRecord(record)!!.content.items)
        assertTrue(store.restoreRecord(record))
        assertTrue(store.undoRemoval(edit))
        assertEquals(items, store.openMixedDraft(edit)!!.items)
        val restored = store.openRecordContent(record)!!.items
        assertEquals(items, restored)
        val sound = restored[2] as ContentItem.Audio
        assertArrayEquals(mp3Bytes, audioOriginals.openOriginal(sound.audioId).use { it.readBytes() })
        assertArrayEquals(pdfBytes, store.openPdf((restored.last() as ContentItem.Pdf).pdfId).use { it.readBytes() })
        playsToEnd(sound.audioId)
    }

    @Test
    fun importedM4aAloneCanBePublishedAndPlayedAfterSourceDeletion() = runBlocking {
        val file = sample("tone-440.m4a")
        val bytes = file.readBytes()
        val result = add(file, ImportType.AUDIO)
        check(file.delete())
        val audio = result.item as ContentItem.Audio
        assertTrue(audio.durationMillis in 1_100..1_400)
        val record = store.completeMixedDraft(result.draftId)
        reopenDatabase()
        assertEquals(audio, store.openRecordContent(record)!!.items.single())
        assertArrayEquals(bytes, audioOriginals.openOriginal(audio.audioId).use { it.readBytes() })
        playsToEnd(audio.audioId)
        assertTrue(store.moveToTrash(record))
        assertTrue(store.deletePermanently(record).deleted)
        assertFalse(audioFile(audio.audioId).exists())
    }

    private suspend fun playsToEnd(id: AudioId) {
        lateinit var playback: LocalAudioPlayback
        instrumentation.runOnMainSync {
            playback = LocalAudioPlayback.get(context)
            playback.play(id)
        }
        try {
            val state = withTimeout(12_000) { playback.state.first { it.hasEnded || it.error != null } }
            assertNull(state.error)
            assertEquals(id, state.audioId)
            assertTrue(state.hasEnded)
        } finally {
            instrumentation.runOnMainSync { playback.release() }
        }
    }

    @Test
    fun badSourceFormatAnchorAndDatabaseFailureLeaveOnlyThePreviousDraftAndOriginal() = runBlocking {
        val pdf = sample("窗框凭证.pdf")
        val mp3 = sample("tone-660.mp3")
        val old = add(pdf, ImportType.PDF)
        val draft = old.draftId
        store.insertText(draft, null, TextEdit("之前写的文字", 6))
        val saved = store.openMixedDraft(draft)
        val before = mediaFiles()
        val expired = source(File(sources, "已删除.pdf"))
        assertTrue(runCatching { store.importMedia(expired, ImportType.PDF, draft) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { add(mp3, ImportType.PDF, draft) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { add(pdf, ImportType.AUDIO, draft) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { add(mp3, ImportType.AUDIO, draft, ItemId("removed")) }.exceptionOrNull() is IllegalArgumentException)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_import BEFORE INSERT ON draft_items
            BEGIN SELECT RAISE(ABORT, 'reject_import'); END""")
        try {
            for (target in listOf(draft, null)) {
                val failure = runCatching { add(pdf, ImportType.PDF, target) }.exceptionOrNull()
                assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_import") == true })
            }
        } finally {
            sql.execSQL("DROP TRIGGER reject_import")
        }
        reopenDatabase()
        assertEquals(saved, store.openMixedDraft(draft))
        assertEquals(listOf(draft), store.listDrafts().map { it.id })
        assertEquals(before, mediaFiles())
        assertTrue(database.importCleanupDao().candidates().isEmpty())
        assertArrayEquals(pdf.readBytes(), store.openPdf((old.item as ContentItem.Pdf).pdfId).use { it.readBytes() })
        assertEquals(draft, add(mp3, ImportType.AUDIO, draft).draftId)
    }

    @Test
    fun missingPublishedPdfBlocksCompletionAndUndoRetainsOriginalUntilPermanentDeletion() = runBlocking {
        val source = sample("窗框凭证.pdf")
        val result = add(source, ImportType.PDF)
        val item = result.item as ContentItem.Pdf
        val file = File(context.filesDir, "pdf-originals/${item.pdfId.value}.pdf")
        check(file.delete())
        assertTrue(runCatching { store.completeMixedDraft(result.draftId) }.exceptionOrNull() is IOException)
        assertEquals(listOf(item), store.openMixedDraft(result.draftId)!!.items)
        assertTrue(store.listRecentRecords().isEmpty())
        source.copyTo(file)
        store.removeItem(result.draftId, item.id)
        assertTrue(file.isFile)
        reopenDatabase()
        assertTrue(store.undoRemoval(result.draftId))
        val record = store.completeMixedDraft(result.draftId)
        val edit = store.editRecord(record)
        store.removeItem(edit, item.id)
        store.discardDraft(edit)
        assertTrue(file.isFile)
        assertTrue(store.moveToTrash(record))
        assertTrue(store.deletePermanently(record).deleted)
        assertFalse(file.exists())
    }

    @Test
    fun videoOriginalSurvivesUndoAndTrashUntilLastReferenceIsDeleted() = runBlocking {
        val file = sample("short-avc-aac.mp4", "m02")
        val bytes = file.readBytes()
        val result = add(file, ImportType.VIDEO)
        val video = result.item as ContentItem.Video
        assertEquals(bytes.size.toLong(), video.sizeBytes)
        assertTrue(requireNotNull(video.durationMillis) in 4_900L..5_200L)
        assertEquals(file.name, video.displayName)
        checkNotNull(store.videoThumbnail(video)).recycle()
        val uri = store.videoSource(video)
        check(file.delete())
        store.removeItem(result.draftId, video.id)
        reopenDatabase()
        assertTrue(store.undoRemoval(result.draftId))
        val record = store.completeMixedDraft(result.draftId)
        val edit = store.editRecord(record)
        store.removeItem(edit, video.id)
        store.discardDraft(edit)
        assertTrue(store.moveToTrash(record))
        assertEquals(video, store.openTrashRecord(record)!!.content.items.single())
        assertTrue(store.restoreRecord(record))
        reopenDatabase()
        assertEquals(video, store.openRecordContent(record)!!.items.single())
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertTrue(store.moveToTrash(record))
        assertTrue(store.deletePermanently(record).deleted)
        assertFalse(File(requireNotNull(uri.path)).exists())
    }

    @Test
    fun mp4WithNoDecodableSamplesLeavesTheDraftAndFilesUntouched() = runBlocking {
        val file = sample("short-avc-aac.mp4", "m02")
        val bytes = file.readBytes()
        val dataBox = bytes.toString(Charsets.ISO_8859_1).indexOf("mdat")
        check(dataBox >= 0)
        file.writeBytes(bytes.copyOf(dataBox + 4))
        val draft = requireNotNull(store.saveDraft(null, TextEdit("保留原文字", 4)))
        val before = store.openMixedDraft(draft)
        val files = mediaFiles()
        val failure = runCatching { add(file, ImportType.VIDEO, draft) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is MediaImportException &&
            listOf("格式", "解码").any { it in failure.message.orEmpty() })
        assertEquals(before, store.openMixedDraft(draft))
        assertEquals(files, mediaFiles())
        assertTrue(database.importCleanupDao().candidates().isEmpty())
    }

    private fun mediaFiles(): Set<File> = listOf("pdf-originals", "audio-originals", "video-originals", "media-imports")
        .flatMap { File(context.filesDir, it).walk().filter(File::isFile).toList() }.toSet()

    @Test
    fun databaseFullDuringBindingPreservesTheDraftAndReleasesTheCopiedFile() = runBlocking {
        retryAfterDatabaseFull(sample("窗框凭证.pdf"), ImportType.PDF)
    }

    @Test
    fun videoDatabaseFullThenRetryPublishesExactlyOneVideo() = runBlocking {
        retryAfterDatabaseFull(sample("short-avc-aac.mp4", "m02"), ImportType.VIDEO)
    }

    private suspend fun retryAfterDatabaseFull(file: File, type: ImportType) {
        val draft = requireNotNull(store.saveDraft(null, TextEdit("保留", 2)))
        val saved = store.openMixedDraft(draft)
        val before = mediaFiles()
        val sql = database.openHelper.writableDatabase
        fun pragma(name: String) = sql.query("PRAGMA $name").use { check(it.moveToFirst()); it.getLong(0) }
        sql.execSQL("CREATE TABLE import_pressure(bytes BLOB)")
        sql.execSQL("""CREATE TRIGGER fill_import BEFORE INSERT ON draft_items
            BEGIN INSERT INTO import_pressure VALUES (zeroblob(1048576)); END""")
        // 页数上限属于 SQLite 连接。让设置和后续草稿事务使用同一写连接。
        val oldLimit = database.withTransaction {
            val old = pragma("max_page_count")
            val limit = pragma("page_count") + 1
            assertEquals(limit, pragma("max_page_count=$limit"))
            old
        }
        try {
            val failure = runCatching { add(file, type, draft) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IOException && failure.message.orEmpty().contains("空间不足"))
        } finally {
            database.withTransaction { pragma("max_page_count=$oldLimit") }
            sql.execSQL("DROP TRIGGER fill_import")
        }
        assertEquals(saved, store.openMixedDraft(draft))
        assertEquals(before, mediaFiles())
        assertTrue(database.importCleanupDao().candidates().isEmpty())
        val retry = add(file, type, draft)
        assertEquals(draft, retry.draftId)
        val record = store.completeMixedDraft(draft)
        assertEquals(requireNotNull(saved).items + retry.item, store.openRecordContent(record)!!.items)
    }

    @Test
    fun cleanupSkipsAnActiveCopyAndCancellationRemovesItsPartialFile() = runBlocking {
        val cleanup = ImportCleanup(database, ImportedOriginals(context, audioOriginals), LocalPhotoOriginals.get(context))
        val ready = CompletableDeferred<File>()
        val copying = launch(Dispatchers.IO) {
            cleanup.importing(ImportAsset.AUDIO) { id ->
                val part = File(context.filesDir, "media-imports/$id.part")
                part.parentFile!!.mkdirs()
                part.writeBytes(byteArrayOf(1, 2))
                ready.complete(part)
                awaitCancellation()
            }
        }
        val part = try {
            withTimeout(5_000) { ready.await() }.also {
                assertEquals(0, store.retryImportCleanup())
                assertTrue(it.isFile)
            }
        } finally {
            copying.cancelAndJoin()
        }
        assertFalse(part.exists())
        assertTrue(database.importCleanupDao().candidates().isEmpty())
    }

    @Test
    fun reopenedQueueRemovesInterruptedImportsAndProtectsBoundOriginal() = runBlocking {
        val source = sample("窗框凭证.pdf")
        val bound = add(source, ImportType.PDF).item as ContentItem.Pdf
        val unfinished = UUID.randomUUID()
        val unbound = UUID.randomUUID()
        val queue = database.importCleanupDao()
        queue.reserve(ImportCleanupEntry(unfinished.toString(), ImportAsset.PDF))
        queue.reserve(ImportCleanupEntry(unbound.toString(), ImportAsset.AUDIO))
        queue.reserve(ImportCleanupEntry(bound.pdfId.value.toString(), ImportAsset.PDF))
        val part = File(context.filesDir, "media-imports/$unfinished.part").apply { writeText("partial") }
        val audio = audioOriginals.importedFile(AudioId(unbound))
        audio.parentFile!!.mkdirs()
        sample("tone-660.mp3").copyTo(audio)
        try {
            reopenDatabase()
            assertEquals(0, store.retryImportCleanup())
            assertFalse(part.exists())
            assertFalse(audio.exists())
            assertArrayEquals(source.readBytes(), store.openPdf(bound.pdfId).use { it.readBytes() })
            assertTrue(database.importCleanupDao().candidates().isEmpty())
        } finally {
            part.delete()
            audio.delete()
        }
    }
}
