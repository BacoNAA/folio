package com.baconaa.folio.records

import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class BackupRestoreTest : AudioStoreFixture() {
    private val password = "备份测试口令-2026".toCharArray()
    @Test fun failedOrStaleRestorePreservesDestinationAndConfirmedRestoreReplacesIt() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val clip = audioClip()
        val draft = store.appendPhotos(null, listOf(photo.id))
        store.insertAudio(draft, null, clip.id, "鸟鸣")
        val source = store.completeMixedDraft(draft)
        val original = store.openRecordContent(source)!!
        val pending = store.editRecord(source)
        store.saveDraftTitle(pending, "新的标题")
        store.reading.saveEnlargedBody(true)
        val bytes = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        store.moveToTrash(source)
        assertTrue(store.deletePermanently(source).deleted)
        assertFalse(originalFile(photo.id).exists())
        assertFalse(audioFile(clip.id).exists())
        store.reading.saveEnlargedBody(false)
        val targetDraft = store.saveDraft(null, TextEdit("目标草稿", 0))!!
        val corrupt = bytes.copyOf(bytes.size / 2)
        assertTrue(runCatching { store.backups.preview(password) { corrupt.inputStream() } }.isFailure)
        assertTrue(runCatching { store.backups.preview("错误测试口令".toCharArray()) { bytes.inputStream() } }.isFailure)
        val tampered = bytes.copyOf().apply { this[lastIndex - 50] = (this[lastIndex - 50].toInt() xor 1).toByte() }
        assertTrue(runCatching { store.backups.preview(password) { tampered.inputStream() } }.isFailure)
        val stale = store.backups.preview(password) { bytes.inputStream() }
        try {
            assertEquals(BackupSummary(1, 0, 1, 0), stale.saved)
            assertEquals(BackupSummary(0, 1, 0, 0), stale.target)
            store.saveDraftTitle(targetDraft, "新改动")
            assertTrue(runCatching { store.backups.restore(stale) { bytes.inputStream() } }.isFailure)
            assertNotNull(store.openMixedDraft(targetDraft))
        } finally { store.backups.release(stale.directory) }
        val current = store.backups.preview(password) { bytes.inputStream() }
        try {
            assertTrue(runCatching { store.backups.restore(current) { corrupt.inputStream() } }.isFailure)
            assertNotNull(store.openMixedDraft(targetDraft))
            store.backups.restore(current) { bytes.inputStream() }
        } finally { store.backups.release(current.directory) }
        reopenDatabase()
        assertEquals(original, store.openRecordContent(source))
        assertPhotos(store.openRecordContent(source)!!.photos, photo)
        playsToEnd(clip.id)
        assertNotNull(store.openMixedDraft(pending))
        assertNull(store.openMixedDraft(targetDraft))
        assertEquals(true, store.reading.readEnlargedBody())
    }

    @Test fun verifiedChecksumsCannotHideAnUnreadableEventTime() = runBlocking {
        val target = store.completeMixedDraft(store.saveDraft(null, TextEdit("原记录", 0), EventTime.Year(2026))!!)
        store.reading.readEnlargedBody()
        val rows = ByteArrayOutputStream()
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("UPDATE text_records SET eventKind='future-time'")
            BackupRows(sql).write(DataOutputStream(rows))
            sql.execSQL("UPDATE text_records SET eventKind='year'")
        }
        val bytes = rows.toByteArray()
        val raw = ByteArrayOutputStream()
        ZipOutputStream(raw).use { zip ->
            zip.putNextEntry(ZipEntry("records.bin"))
            zip.write(bytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest"))
            val end = DataOutputStream(zip)
            end.writeUTF("Folio backup")
            end.writeInt(1)
            end.writeLong(1234L)
            end.writeUTF("test")
            end.writeInt(1)
            end.writeUTF("records.bin")
            end.writeLong(bytes.size.toLong())
            end.write(MessageDigest.getInstance("SHA-256").digest(bytes))
            zip.closeEntry()
        }
        val encrypted = ByteArrayOutputStream()
        BackupCipher.encrypt(raw.toByteArray().inputStream(), encrypted, password)
        val failure = runCatching { store.backups.preview(password) { encrypted.toByteArray().inputStream() } }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("未知事件时间") == true })
        assertEquals(EventTime.Year(2026), store.openRecordContent(target)!!.times.eventTime)
    }

    @Test fun interruptionInsideSwitchRollsBackRowsAndAllowsFreshRetry() = runBlocking {
        val record = store.completeMixedDraft(store.saveDraft(null, TextEdit("已备份", 0))!!)
        val bytes = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        val local = store.saveDraft(null, TextEdit("仅在目标", 0))!!
        val sql = database.openHelper.writableDatabase
        val preview = store.backups.preview(password) { bytes.inputStream() }
        try {
            sql.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON text_records BEGIN SELECT RAISE(ABORT,'test interruption'); END")
            try {
                assertTrue(runCatching { store.backups.restore(preview) { bytes.inputStream() } }.isFailure)
            } finally { sql.execSQL("DROP TRIGGER fail_restore") }
        } finally { store.backups.release(preview.directory) }
        reopenDatabase()
        assertNotNull(store.openMixedDraft(local))
        val fresh = store.backups.preview(password) { bytes.inputStream() }
        try { store.backups.restore(fresh) { bytes.inputStream() } } finally { store.backups.release(fresh.directory) }
        assertNull(store.openMixedDraft(local))
        assertNotNull(store.openRecordContent(record))
    }
}
