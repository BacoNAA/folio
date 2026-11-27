package com.baconaa.folio.records

import android.graphics.Bitmap
import android.graphics.Color
import com.baconaa.folio.media.StampArtwork
import com.baconaa.folio.media.StampCrop
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class TagStampTest : PhotoStoreFixture() {
    @Test fun croppedStampPublishesOnceAndBackingUpPreservesSharedPatternAndOriginal() = runBlocking {
        val first = store.saveDraft(null, TextEdit("食谱", 0))!!
        val apron = importPhoto(Color.RED)
        store.insertPhoto(first, null, apron.id, "围裙")
        val fabric = Bitmap.createBitmap(6, 3, Bitmap.Config.ARGB_8888)
        fabric.eraseColor(Color.RED)
        for (x in 3..5) for (y in 0..2) fabric.setPixel(x, y, Color.BLUE)
        val pattern = StampArtwork.render(fabric, StampCrop(1f, .5f, 1f))
        assertEquals(Color.BLUE, StampArtwork.decode(pattern).getPixel(48, 48))
        val tagId = store.saveStamp(first, null, "家的味道", pattern)
        val privateTag = store.openDraftTags(first)!!.single()
        assertTrue(store.listTags().isEmpty())
        assertArrayEquals(pattern, store.readStamp(privateTag))
        val other = store.saveDraft(null, TextEdit("饭桌", 0))!!
        assertTrue(runCatching { store.saveStamp(other, tagId, "其他草稿", pattern) }.isFailure)
        val recipe = store.completeMixedDraft(first)
        store.saveDraftTags(other, listOf(tagId))
        val table = store.completeMixedDraft(other)
        val replacement = StampArtwork.icon("★")
        store.saveStamp(null, tagId, "外婆的厨房", replacement)
        reopenDatabase()
        for (id in listOf(recipe, table)) {
            val current = store.openRecordContent(id)!!.tags.single()
            assertEquals(tagId, current.id)
            assertEquals("外婆的厨房", current.name)
            assertArrayEquals(replacement, store.readStamp(current))
        }
        assertEquals(setOf(recipe, table), store.queryRecords("外婆的厨房").map { it.id }.toSet())
        assertTrue(store.queryRecords("家的味道").isEmpty())
        assertPhotos(store.openRecordContent(recipe)!!.photos, apron)
        val abandoned = store.saveDraft(null, TextEdit("试样", 0))!!
        val hidden = store.saveStamp(abandoned, null, "试样图案", pattern)
        val hiddenTag = store.openDraftTags(abandoned)!!.single()
        store.discardDraft(abandoned)
        assertNull(database.tagDao().tag(hidden.value))
        assertNull(store.readStamp(hiddenTag))
        assertTrue(runCatching { store.saveStamp(null, tagId, "误改名", ByteArray(18)) }.isFailure)
        assertTrue(runCatching { store.saveStamp(null, tagId, "误改名", ByteArray(StampArtwork.MAX_BYTES + 1)) }.isFailure)
        assertEquals("外婆的厨房", store.listTags().single().name)

        val password = "图章完整备份测试".toCharArray()
        val bytes = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        store.saveStamp(null, tagId, "临时修改", pattern)
        val backup = store.backups.preview(password) { bytes.inputStream() }
        try { store.backups.restore(backup) { bytes.inputStream() } }
        finally { store.backups.release(backup.directory) }
        reopenDatabase()
        assertArrayEquals(replacement, store.readStamp(store.listTags("外婆的厨房").single()))
        assertPhotos(store.openRecordContent(recipe)!!.photos, apron)
    }
}
