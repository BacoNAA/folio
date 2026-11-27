package com.baconaa.folio.records

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Process
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** 分五次启动同一测试。仅显式传 d03Phase 时才运行，kill 阶段只终止自身进程。 */
class BackupInterruptionProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = context.getSharedPreferences("d03-interruption-probe", Context.MODE_PRIVATE)
    private val archive get() = File(context.filesDir, "d03-interruption-probe.folio")
    private val password get() = "离线测试样本口令".toCharArray()
    private val photos get() = LocalPhotoOriginals.get(context)
    private val dbName = "d03-interruption-probe.db"
    private fun value(key: String) = requireNotNull(marker.getString(key, null))
    private fun photoId(key: String) = PhotoId(UUID.fromString(value(key + "Photo")))
    private fun sha(id: PhotoId): String = photos.openOriginal(id).use { stream ->
        MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test fun runPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("d03Phase")
        assumeNotNull(phase)
        val builder = Room.databaseBuilder(context, FolioDatabase::class.java, dbName)
        var armed = false
        if (phase == "killSwitch") builder.openHelperFactory(observeSwitch { db ->
            if (armed) {
                check(db.inTransaction())
                assertEquals(value("sourceSha"), sha(photoId("source")))
                killHere("switch")
            }
        })
        val db = builder.build()
        val store = LocalTextStore(db, context)
        try {
            when (phase) {
                "seed" -> {
                    check(!archive.exists() && store.listRecentRecords().isEmpty()) { "请使用干净探针库。" }
                    val source = seed(store, "source", Color.GREEN)
                    val draft = store.saveDraft(null, TextEdit("来源待续写", 1))!!
                    check(marker.edit().putString("sourceDraft", draft.value).commit())
                    archive.outputStream().use { store.backups.writeProtected(it, password) }
                    store.discardDraft(draft)
                    store.moveToTrash(source)
                    store.deletePermanently(source)
                    seed(store, "target", Color.BLUE)
                    val target = store.saveDraft(null, TextEdit("目标待续写", 2))!!
                    check(marker.edit().putString("targetDraft", target.value).commit())
                    verify(store, "target")
                }
                "killPrepare" -> {
                    verify(store, "target")
                    store.backups.preview(password) {
                        object : FilterInputStream(archive.inputStream()) {
                            var read = 0L
                            override fun read(b: ByteArray, offset: Int, length: Int): Int {
                                if (read >= archive.length() / 2) killHere("prepare")
                                val n = super.read(b, offset, minOf(length, 64))
                                if (n > 0) read += n
                                return n
                            }
                        }
                    }
                    error("没有到达预检中断点")
                }
                "killSwitch" -> {
                    verifyRestart("prepare")
                    verify(store, "target")
                    val preview = store.backups.preview(password, archive::inputStream)
                    armed = true
                    store.backups.restore(preview, archive::inputStream)
                    error("没有到达切换中断点")
                }
                "retry" -> {
                    verifyRestart("switch")
                    verify(store, "target")
                    assertEquals(value("sourceSha"), sha(photoId("source")))
                    store.retryMediaCleanup()
                    assertTrue(runCatching { sha(photoId("source")) }.isFailure)
                    verify(store, "target")
                    val preview = store.backups.preview(password, archive::inputStream)
                    try { store.backups.restore(preview, archive::inputStream) } finally { store.backups.release(preview.directory) }
                    verify(store, "source")
                }
                "verify" -> {
                    verify(store, "source")
                    store.retryMediaCleanup()
                    assertTrue(runCatching { sha(photoId("target")) }.isFailure)
                    store.discardDraft(DraftId(value("sourceDraft")))
                    val source = RecordId(value("sourceRecord"))
                    store.moveToTrash(source)
                    store.deletePermanently(source)
                    check(archive.delete())
                    check(marker.edit().clear().commit())
                }
                else -> error("未知验收阶段")
            }
        } finally { db.close() }
        if (phase == "verify") check(context.deleteDatabase(dbName))
    }

    private fun killHere(stage: String): Nothing {
        check(marker.edit().putString("stage", stage).putInt("pid", Process.myPid()).commit())
        Process.killProcess(Process.myPid())
        error("进程仍在运行")
    }

    private fun verifyRestart(previous: String) {
        assertEquals(previous, value("stage"))
        assertNotEquals(marker.getInt("pid", -1), Process.myPid())
    }

    private suspend fun seed(store: LocalTextStore, key: String, color: Int): RecordId {
        val file = File.createTempFile("d03-sample", ".png", context.cacheDir)
        val photo = try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            photos.importPhoto(Uri.fromFile(file))
        } finally { file.delete() }
        val draft = store.appendPhotos(null, listOf(photo))
        val record = store.completeMixedDraft(draft)
        check(marker.edit().putString(key + "Record", record.value).putString(key + "Photo", photo.value.toString())
            .putString(key + "Sha", sha(photo)).commit())
        return record
    }

    private suspend fun verify(store: LocalTextStore, key: String) {
        val record = RecordId(value(key + "Record"))
        val draft = DraftId(value(key + "Draft"))
        assertEquals(listOf(record), store.listRecentRecords().map { it.id })
        assertEquals(listOf(draft), store.listDrafts().map { it.id })
        assertEquals(if (key == "source") "来源待续写" else "目标待续写", store.openDraft(draft)?.text)
        assertEquals(listOf(photoId(key)), store.openRecordContent(record)?.photos)
        assertEquals(value(key + "Sha"), sha(photoId(key)))
    }

    private fun observeSwitch(beforeDelete: (SupportSQLiteDatabase) -> Unit) = object : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            fun observe(db: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by db {
                override fun execSQL(sql: String) {
                    if (sql == "DELETE FROM `text_records`") beforeDelete(db)
                    db.execSQL(sql)
                }
            }
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = observe(helper.writableDatabase)
                override val readableDatabase get() = observe(helper.readableDatabase)
            }
        }
    }
}
