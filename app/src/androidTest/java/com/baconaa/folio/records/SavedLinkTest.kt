package com.baconaa.folio.records

import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class SavedLinkTest : PhotoStoreFixture() {
    @Test fun incompleteEditKeepsThePublishedLinkAndScreenshotUntilCompletion() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val link = ContentItem.Link(newItemId(), "https://example.invalid/filter", "滤芯说明", "蓝色边框", photo.id)
        val draft = store.insertLink(null, null, link)
        val id = store.completeMixedDraft(draft)
        val edit = store.editRecord(id)
        val pending = link.copy(address = "https://", title = "", note = "橙色边框", screenshotId = null)
        store.editLink(edit, pending)
        reopenDatabase()
        assertEquals(pending, store.openMixedDraft(edit)!!.items.single())
        val invalid = runCatching { store.completeMixedDraft(edit) }.exceptionOrNull() as InvalidLinkAddress
        assertEquals(link.id, invalid.itemId)
        assertEquals(link, store.openRecordContent(id)!!.items.single())
        assertArrayEquals(photo.bytes, originalFile(photo.id).readBytes())
        assertEquals(PreviewContentItem.Link(link.id, link.title),
            store.queryRecords(link.title, mediaTypes = setOf(MediaFilter.LINK)).single().preview.items.single())
        assertEquals(id, store.search("蓝色边框").single().id)
        assertTrue(store.search("橙色边框").isEmpty())
        assertTrue(store.queryRecords(mediaTypes = setOf(MediaFilter.PHOTO, MediaFilter.TEXT_ONLY)).isEmpty())
        val repaired = pending.copy(address = "http://localhost/new")
        store.editLink(edit, repaired)
        assertEquals(id, store.completeMixedDraft(edit))
        assertEquals(id, store.completeMixedDraft(edit))
        assertFalse(originalFile(photo.id).exists())
        assertTrue(store.search("滤芯说明").isEmpty())
        assertEquals(id, store.search("橙色边框").single().id)
        assertEquals(PreviewContentItem.Link(link.id, repaired.address),
            store.queryRecords("localhost/new").single().preview.items.single())
    }

    @Test fun screenshotCopyStaysWithItsLinkThroughUndoTrashAndLastReference() = runBlocking {
        val source = importPhoto(Color.RED)
        val uri = Uri.fromFile(originalFile(source.id))
        val draft = store.importLinkScreenshot(uri, null, null)
        val link = store.openMixedDraft(draft)!!.items.single() as ContentItem.Link
        val screenshot = requireNotNull(link.screenshotId)
        val before = originalFile(screenshot).parentFile!!.list()!!.toSet()
        assertTrue(runCatching { store.importLinkScreenshot(uri, draft, newItemId()) }.isFailure)
        assertEquals(before, originalFile(screenshot).parentFile!!.list()!!.toSet())
        assertEquals(emptyList<String>(), database.importCleanupDao().candidates())
        assertEquals(link, store.openMixedDraft(draft)!!.items.single())

        store.importLinkScreenshot(uri, draft, link.id)
        val replacement = store.openMixedDraft(draft)!!.items.single() as ContentItem.Link
        val replacementId = requireNotNull(replacement.screenshotId)
        assertFalse(originalFile(screenshot).exists())
        check(originalFile(source.id).delete())
        store.removeItem(draft, link.id)
        reopenDatabase()
        assertTrue(store.undoRemoval(draft))
        assertEquals(replacement, store.openMixedDraft(draft)!!.items.single())
        store.editLink(draft, replacement.copy(address = "https://example.invalid/receipt"))
        val record = store.completeMixedDraft(draft)
        val other = store.completeMixedDraft(store.appendPhotos(null, listOf(replacementId)))
        assertTrue(store.moveToTrash(record))
        assertTrue(store.queryRecords(mediaTypes = setOf(MediaFilter.LINK)).isEmpty())
        assertTrue(store.restoreRecord(record))
        assertArrayEquals(source.bytes, originalFile(replacementId).readBytes())
        assertTrue(store.moveToTrash(record))
        assertTrue(store.deletePermanently(record).deleted)
        assertTrue(originalFile(replacementId).isFile)
        assertTrue(store.moveToTrash(other))
        assertTrue(store.deletePermanently(other).deleted)
        assertFalse(originalFile(replacementId).exists())
    }
}
