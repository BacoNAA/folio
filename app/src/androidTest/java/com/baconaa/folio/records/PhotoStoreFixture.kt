package com.baconaa.folio.records

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before

internal abstract class PhotoStoreFixture {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "photo-records-${UUID.randomUUID()}.db"
    private val originals = LocalPhotoOriginals.get(context)
    private val imported = mutableListOf<PhotoId>()
    private lateinit var database: FolioDatabase
    protected lateinit var store: LocalTextStore

    @Before
    fun openDatabase() {
        database = Room.databaseBuilder(context, FolioDatabase::class.java, databaseName).build()
        store = LocalTextStore(database, originals)
    }

    protected fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    @After
    fun cleanUp() {
        database.close()
        context.deleteDatabase(databaseName)
        imported.forEach { File(context.filesDir, "photo-originals/${it.value}").delete() }
    }

    protected class ImportedPhoto(val id: PhotoId, val bytes: ByteArray)

    protected suspend fun importPhoto(color: Int): ImportedPhoto {
        val source = File.createTempFile("photo-source-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val bytes = source.readBytes()
            val id = originals.importPhoto(Uri.fromFile(source))
            imported.add(id)
            return ImportedPhoto(id, bytes)
        } finally {
            check(source.delete())
        }
    }

    protected fun assertPhotos(actual: List<PhotoId>?, vararg expected: ImportedPhoto) {
        assertEquals(expected.map { it.id }, actual)
        requireNotNull(actual).forEachIndexed { index, id ->
            assertArrayEquals(expected[index].bytes, originals.openOriginal(id).use { it.readBytes() })
        }
    }
}
