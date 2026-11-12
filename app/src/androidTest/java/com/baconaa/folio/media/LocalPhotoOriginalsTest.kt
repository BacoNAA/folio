package com.baconaa.folio.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPhotoOriginalsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val originals = LocalPhotoOriginals.get(context)
    private val directory = File(context.filesDir, "photo-originals")
    private val sources = mutableListOf<Uri>()
    private val imported = mutableListOf<PhotoId>()

    @After
    fun cleanUp() {
        sources.forEach { resolver.delete(it, null, null) }
        imported.forEach { File(directory, it.value.toString()).delete() }
    }

    private fun createImage(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "folio-test-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        sources.add(uri)
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun originalPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    @Test
    fun originalBytesCanBeReopenedByIdAfterSourceIsDeleted() = runBlocking {
        val bytes = originalPng()
        val source = createImage(bytes)
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertEquals(1, resolver.update(source, values, null, null))
        val id = originals.importPhoto(source).also(imported::add)
        assertEquals(1, resolver.delete(source, null, null))
        sources.remove(source)

        val reopened = PhotoId(UUID.fromString(id.value.toString()))
        assertArrayEquals(bytes, originals.openOriginal(reopened).use { it.readBytes() })
    }

    @Test
    fun unreadableImageLeavesExistingOriginalIntactAndNoPartialCopy() = runBlocking {
        val bytes = originalPng()
        val good = originals.importPhoto(createImage(bytes)).also(imported::add)
        val before = directory.walkTopDown().filter { it.isFile }.toSet()
        val malformed = createImage("not a PNG".toByteArray())

        val failure = runCatching { originals.importPhoto(malformed).also(imported::add) }.exceptionOrNull()

        assertTrue("Invalid image should fail to import", failure is IOException)
        assertEquals(before, directory.walkTopDown().filter { it.isFile }.toSet())
        assertArrayEquals(bytes, originals.openOriginal(good).use { it.readBytes() })
    }
}
