package com.baconaa.folio.records

import android.graphics.Color
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class BackupExportTest : AudioStoreFixture() {
    @Test fun exportedRowsAndPinnedOriginalsRepresentOneMoment() = runBlocking {
        val photo = importPhoto(Color.RED)
        val clip = audioClip()
        val trashed = publishAudio(clip.id)
        store.moveToTrash(trashed)
        val draft = store.appendPhotos(null, listOf(photo.id))
        val note = store.completeMixedDraft(store.saveDraft(null, TextEdit("秋游", 0))!!)
        store.editRecord(note)
        val bytes = ByteArrayOutputStream()
        var changed = false
        val sink = object : FilterOutputStream(bytes) {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!changed) {
                    changed = true
                    runBlocking {
                        store.discardDraft(draft)
                        store.completeMixedDraft(store.saveDraft(null, TextEdit("新增记录", 0))!!)
                    }
                }
                out.write(buffer, offset, length)
            }
        }
        val summary = store.backups.writeArchive(sink)
        assertEquals(BackupSummary(1, 1, 1, 1), summary)
        assertTrue(changed)
        assertEquals(2, store.listRecentRecords().size)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertArrayEquals(photo.bytes, entries["photo-originals/${photo.id.value}"])
        assertArrayEquals(audioFile(clip.id).readBytes(), entries["audio-originals/${clip.id.value}.m4a"])
        assertFalse(entries.getValue("records.bin").toString(Charsets.UTF_8).contains("新增记录"))
        assertEquals("manifest", entries.keys.last())
        val clean = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FolioDatabase::class.java).build()
        try {
            clean.withTransaction {
                val rows = BackupRows(clean.openHelper.writableDatabase)
                rows.replace(DataInputStream(entries.getValue("records.bin").inputStream()))
                assertEquals(summary, rows.summary())
            }
        } finally { clean.close() }
    }

    @Test fun unavailableCoverStillExportsButInconsistentChapterPeriodIsRejected() = runBlocking {
        val source = store.completeMixedDraft(store.appendPhotos(null, listOf(importPhoto(Color.BLUE).id)))
        val id = RecordGroupId("old-house")
        store.saveChapter(id, null, ChapterProfile("老房子", "2023", "2024", cover = store.chapterPhotos().single().cover))
        store.moveToTrash(source)
        assertEquals(1L, store.backups.writeArchive(ByteArrayOutputStream()).trash)
        database.openHelper.writableDatabase.execSQL("UPDATE life_chapters SET end='2020' WHERE groupId='old-house'")
        val failed = runCatching { store.backups.writeArchive(ByteArrayOutputStream()) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }

    @Test fun interruptedOutputLeavesTheSourceDraftReadable() = runBlocking {
        val draft = store.saveDraft(null, TextEdit("仅在草稿中", 0))!!
        val failed = object : OutputStream() { override fun write(b: Int) { throw IOException("目标空间不足") } }
        assertTrue(runCatching { store.backups.writeProtected(failed, "仅供验收的八字密码".toCharArray()) }.isFailure)
        assertEquals("仅在草稿中", store.openDraft(draft)!!.text)
    }

    @Test fun missingOriginalPreventsAnySuccessfulOutput() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val draft = store.appendPhotos(null, listOf(photo.id))
        assertTrue(originalFile(photo.id).delete())
        val sink = ByteArrayOutputStream()
        assertTrue(runCatching { store.backups.writeArchive(sink) }.isFailure)
        assertEquals(0, sink.size())
        originalFile(photo.id).writeText("not a photo")
        assertTrue(runCatching { store.backups.writeArchive(sink) }.isFailure)
        assertEquals(0, sink.size())
        assertNotNull(store.openMixedDraft(draft))
    }
}
