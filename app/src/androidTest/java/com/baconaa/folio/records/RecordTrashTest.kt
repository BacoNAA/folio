package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordTrashTest : PhotoStoreFixture() {
    @Test
    fun trashHidesEditingDraftAndRestoresItsContentCursorAndUndoAfterReopen() = runBlocking {
        val oldPhoto = importPhoto(Color.BLUE)
        val newPhoto = importPhoto(Color.RED)
        val initial = requireNotNull(store.saveDraft(null, TextEdit("旧桥", 2)))
        store.appendPhotos(initial, listOf(oldPhoto.id))
        val record = store.completeMixedDraft(initial)
        val published = requireNotNull(store.openRecordContent(record))
        val originalRow = requireNotNull(database.textDao().activeRecord(record.value))
        val draft = store.editRecord(record)
        val text = published.items.first().id
        store.removeItem(draft, published.items.last().id)
        val inserted = store.insertPhoto(draft, null, newPhoto.id, "未完成的新照片")
        store.moveItem(draft, inserted, MoveDirection.UP)
        store.editTextItem(draft, text, TextEdit("新桥🌉边", 4))
        val savedDraft = requireNotNull(store.openMixedDraft(draft))
        val independent = requireNotNull(store.saveDraft(null, TextEdit("独立草稿", 2)))

        assertNull(store.openTrashRecord(record))
        assertTrue(store.moveToTrash(record))
        val trashed = requireNotNull(store.openTrashRecord(record))
        assertEquals(published, trashed.content)
        assertTrue(trashed.hasDraft)
        assertPhotos(trashed.content.photos, oldPhoto)
        assertFalse(store.moveToTrash(record))
        assertEquals(trashed, store.openTrashRecord(record))
        assertEquals(
            TrashPreview(record, "旧桥", oldPhoto.id, trashed.deletedAt, hasDraft = true),
            store.listTrash().single(),
        )
        assertNull(store.openRecord(record))
        assertNull(store.openRecordContent(record))
        assertTrue(store.search("旧桥").isEmpty())
        assertEquals(listOf(independent), store.listDrafts().map { it.id })
        assertNull(store.openDraft(draft))
        assertNull(store.openMixedDraft(draft))
        assertNull(store.openDraftPhotos(draft))
        assertFalse(store.canUndoRemoval(draft))

        // 已打开的编辑页可能仍持有草稿 ID；删除后不能借此改写回收站内容。
        assertTrue(runCatching { store.editRecord(record) }.isFailure)
        assertTrue(runCatching { store.saveDraft(draft, TextEdit("错误覆盖", 4)) }.isFailure)
        assertTrue(runCatching { store.editPhotoCaption(draft, inserted, "错误说明") }.isFailure)
        assertTrue(runCatching { store.moveItem(draft, inserted, MoveDirection.DOWN) }.isFailure)
        assertTrue(runCatching { store.removeItem(draft, inserted) }.isFailure)
        assertTrue(runCatching { store.completeMixedDraft(draft) }.isFailure)
        assertFalse(store.undoRemoval(draft))
        store.discardDraft(draft)
        reopenDatabase()

        assertEquals(trashed, store.openTrashRecord(record))
        assertTrue(store.restoreRecord(record))
        assertFalse(store.restoreRecord(record))
        assertTrue(store.listTrash().isEmpty())
        assertNull(store.openTrashRecord(record))
        assertEquals(originalRow, database.textDao().activeRecord(record.value))
        assertEquals(published, store.openRecordContent(record))
        assertEquals(record, store.search("旧桥").single().id)
        assertEquals(draft, store.editRecord(record))
        assertEquals(savedDraft, store.openMixedDraft(draft))
        assertEquals(setOf(draft, independent), store.listDrafts().map { it.id }.toSet())
        assertPhotos(store.openDraftPhotos(draft), newPhoto)
        assertTrue(store.canUndoRemoval(draft))
        assertTrue(store.undoRemoval(draft))
        assertPhotos(store.openDraftPhotos(draft), newPhoto, oldPhoto)

        val restoredItems = requireNotNull(store.openMixedDraft(draft)).items
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(restoredItems, store.openRecordContent(record)?.items)
        assertTrue(store.search("旧桥").isEmpty())
        assertEquals(record, store.search("新桥").single().id)
        assertEquals(TextEdit("独立草稿", 2), store.openDraft(independent))
    }

    @Test
    fun completionRetriesCannotReviveDeletedRecordAndResumeAfterRestore() = runBlocking {
        val original = TextEdit("桥边", 2)
        val initial = requireNotNull(store.saveDraft(null, original))
        val record = store.completeDraft(initial, original)
        val edited = store.editRecord(record)
        val latest = TextEdit("桥边新记", 4)
        store.completeDraft(edited, latest)

        assertTrue(store.moveToTrash(record))
        reopenDatabase()
        assertTrue(runCatching { store.completeDraft(initial, original) }.isFailure)
        assertTrue(runCatching { store.completeDraft(edited, latest) }.isFailure)
        assertNull(store.openRecord(record))
        val trashed = requireNotNull(store.openTrashRecord(record))
        assertEquals(latest.text, trashed.content.text)
        assertFalse(trashed.hasDraft)
        assertTrue(store.listDrafts().isEmpty())

        assertTrue(store.restoreRecord(record))
        assertEquals(record, store.completeDraft(initial, original))
        assertEquals(record, store.completeDraft(edited, latest))
        assertEquals(latest.text, store.openRecord(record))
        assertFalse(store.restoreRecord(record))
        assertFalse(store.moveToTrash(RecordId("missing-record")))
        assertFalse(store.restoreRecord(RecordId("missing-record")))
        assertNull(store.openTrashRecord(RecordId("missing-record")))
    }

    @Test
    fun trashListsNewestDeletionFirstAndRestoringOneKeepsTheOther() = runBlocking {
        var timestamp = 1_000L
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LocalTextStore(database, LocalPhotoOriginals.get(context), now = { timestamp })
        val firstDraft = requireNotNull(store.saveDraft(null, TextEdit("第一条", 3)))
        val first = store.completeMixedDraft(firstDraft)
        timestamp = 2_000L
        val secondDraft = requireNotNull(store.saveDraft(null, TextEdit("第二条", 3)))
        val second = store.completeMixedDraft(secondDraft)
        timestamp = 3_000L
        assertTrue(store.moveToTrash(first))
        timestamp = 4_000L
        assertTrue(store.moveToTrash(second))
        reopenDatabase()

        assertEquals(listOf(second, first), store.listTrash().map { it.id })
        assertEquals(listOf(4_000L, 3_000L), store.listTrash().map { it.deletedAt })
        assertTrue(store.restoreRecord(second))
        assertEquals(listOf(first), store.listTrash().map { it.id })
        assertEquals("第二条", store.openRecord(second))
        assertNull(store.openRecord(first))
    }
}
