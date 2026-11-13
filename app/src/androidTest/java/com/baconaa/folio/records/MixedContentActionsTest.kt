package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MixedContentActionsTest : PhotoStoreFixture() {
    @Test
    fun moveAndUndoKeepItemIdentityPhotoCaptionAndOtherEdits() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val initial = store.appendPhotos(null, listOf(photo.id))
        val record = store.completeMixedDraft(initial)
        val draft = store.editRecord(record)
        val first = store.openMixedDraft(draft)!!.items.single()
        val text = store.insertText(draft, null, TextEdit("桥边夜色", 1))
        val second = store.insertPhoto(draft, null, photo.id, "同一张照片的第二处说明")

        store.moveItem(draft, text, MoveDirection.UP)
        store.moveItem(draft, text, MoveDirection.UP) // 首项保持原位。
        val moved = store.openMixedDraft(draft)!!
        assertEquals(listOf(text, first.id, second), moved.items.map { it.id })
        assertEquals(text, moved.activeItemId)
        store.moveItem(draft, text, MoveDirection.DOWN)
        store.removeItem(draft, second)
        assertEquals(text, store.openMixedDraft(draft)!!.activeItemId)
        val appended = store.insertText(draft, null, TextEdit("桥头", 2))
        store.editTextItem(draft, text, TextEdit("改后的正文", 2))
        reopenDatabase()
        assertTrue(store.canUndoRemoval(draft))
        assertEquals(listOf(first), store.openRecordContent(record)!!.items)
        assertTrue(store.undoRemoval(draft))
        val restored = store.openMixedDraft(draft)!!
        assertEquals(listOf(
            first, ContentItem.Text(text, "改后的正文"),
            ContentItem.Photo(second, photo.id, "同一张照片的第二处说明"), ContentItem.Text(appended, "桥头"),
        ), restored.items)
        assertEquals(second, restored.activeItemId)
        assertFalse(store.undoRemoval(draft))
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(restored.items, store.openRecordContent(record)!!.items)
    }

    @Test
    fun onlyLatestRemovalCanBeRestoredAndEmptyDraftRemainsResumable() = runBlocking {
        val draft = requireNotNull(store.saveDraft(null, TextEdit("前文", 1)))
        val first = store.openMixedDraft(draft)!!.items.single()
        val last = store.insertText(draft, null, TextEdit("桥🌉边", 3))
        store.removeItem(draft, first.id)
        store.removeItem(draft, last)
        reopenDatabase()

        assertEquals(draft, store.listDrafts().single().id)
        assertEquals(emptyList<ContentItem>(), store.openMixedDraft(draft)!!.items)
        assertTrue(runCatching { store.completeMixedDraft(draft) }.isFailure)
        assertTrue(store.undoRemoval(draft))
        assertEquals(MixedDraft(listOf(ContentItem.Text(last, "桥🌉边")), last, 3), store.openMixedDraft(draft))
        assertFalse(store.canUndoRemoval(draft))

        store.removeItem(draft, last)
        store.discardDraft(draft)
        assertFalse(store.undoRemoval(draft))
        assertNull(database.removedItemDao().get(draft.value))
    }
}
