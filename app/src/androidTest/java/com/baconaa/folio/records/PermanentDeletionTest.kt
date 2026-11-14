package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baconaa.folio.media.PhotoId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class PermanentDeletionTest : PhotoStoreFixture() {
    @Test
    fun deletingTrashedRecordAlsoRemovesEditingDraftUndoAndPrivateOriginals() = runBlocking {
        val publishedPhoto = importPhoto(Color.BLUE)
        val draftPhoto = importPhoto(Color.RED)
        val removedPhoto = importPhoto(Color.GREEN)
        val initial = store.appendPhotos(null, listOf(publishedPhoto.id))
        val record = store.completeMixedDraft(initial)
        val completedEdit = store.editRecord(record)
        store.completeMixedDraft(completedEdit)
        val draft = store.editRecord(record)
        val originalItem = requireNotNull(store.openMixedDraft(draft)).items.single()
        store.removeItem(draft, originalItem.id)
        store.insertPhoto(draft, null, draftPhoto.id, "未完成照片")
        val removed = store.insertPhoto(draft, null, removedPhoto.id)
        store.removeItem(draft, removed)

        assertTrue(runCatching { store.deletePermanently(record) }.isFailure)
        assertPhotos(store.openRecordContent(record)?.photos, publishedPhoto)
        assertTrue(store.moveToTrash(record))
        reopenDatabase()
        assertEquals(PermanentDeletionResult(deleted = true, pendingPhotoCount = 0), store.deletePermanently(record))
        assertTrue(store.listTrash().isEmpty())
        assertTrue(store.listDrafts().isEmpty())
        assertNull(store.openRecordContent(record))
        assertNull(store.openTrashRecord(record))
        assertNull(store.openMixedDraft(draft))
        assertNull(database.textDao().recordEdit(completedEdit.value))
        assertNull(database.textDao().recordEdit(draft.value))
        assertNull(database.removedItemDao().get(draft.value))
        assertFalse(store.undoRemoval(draft))
        assertFalse(store.restoreRecord(record))
        assertTrue(runCatching { store.completeMixedDraft(initial) }.isFailure)
        assertTrue(runCatching { store.completeMixedDraft(completedEdit) }.isFailure)
        assertTrue(runCatching { store.completeMixedDraft(draft) }.isFailure)
        assertEquals(PermanentDeletionResult(deleted = false, pendingPhotoCount = 0), store.deletePermanently(record))
        listOf(publishedPhoto, draftPhoto, removedPhoto).forEach {
            assertFalse(originalFile(it.id).exists())
        }
    }

    @Test
    fun sharedOriginalSurvivesEachReferenceKindUntilTheFinalUndoIsReleased() = runBlocking {
        val shared = importPhoto(Color.BLUE)
        val active = publishPhoto(shared.id)
        val firstDeleted = publishPhoto(shared.id)
        store.moveToTrash(firstDeleted)
        store.deletePermanently(firstDeleted)
        assertPhotos(store.openRecordContent(active)?.photos, shared)

        val temporaryDraft = store.appendPhotos(null, listOf(shared.id))
        store.moveToTrash(active)
        store.discardDraft(temporaryDraft)
        assertPhotos(store.openTrashRecord(active)?.content?.photos, shared)

        val textDraft = requireNotNull(store.saveDraft(null, TextEdit("正文没有照片", 6)))
        val hiddenOwner = store.completeMixedDraft(textDraft)
        val hiddenDraft = store.editRecord(hiddenOwner)
        store.appendPhotos(hiddenDraft, listOf(shared.id))
        store.moveToTrash(hiddenOwner)
        store.deletePermanently(active)
        assertNull(store.openDraftPhotos(hiddenDraft))
        val hiddenRecord = requireNotNull(store.openTrashRecord(hiddenOwner))
        assertTrue(hiddenRecord.hasDraft)
        assertTrue(hiddenRecord.content.photos.isEmpty())
        assertArrayEquals(shared.bytes, originalFile(shared.id).readBytes())

        val draft = store.appendPhotos(null, listOf(shared.id))
        store.deletePermanently(hiddenOwner)
        assertPhotos(store.openDraftPhotos(draft), shared)

        store.removeItem(draft, requireNotNull(store.openMixedDraft(draft)).items.single().id)
        val lastRecord = publishPhoto(shared.id)
        store.moveToTrash(lastRecord)
        store.deletePermanently(lastRecord)
        reopenDatabase()
        assertTrue(store.canUndoRemoval(draft))
        assertArrayEquals(shared.bytes, originalFile(shared.id).readBytes())
        assertTrue(store.undoRemoval(draft))
        assertPhotos(store.openDraftPhotos(draft), shared)
        store.removeItem(draft, requireNotNull(store.openMixedDraft(draft)).items.single().id)
        store.discardDraft(draft)
        assertFalse(originalFile(shared.id).exists())
    }

    @Test
    fun completionDiscardAndNextRemovalCleanReleasedPhotosWithoutTouchingNewImports() = runBlocking {
        val oldPhoto = importPhoto(Color.BLUE)
        val replacement = importPhoto(Color.RED)
        val discarded = importPhoto(Color.GREEN)
        val previousUndo = importPhoto(Color.CYAN)
        val latestUndo = importPhoto(Color.YELLOW)
        val unattached = importPhoto(Color.MAGENTA)
        val record = publishPhoto(oldPhoto.id)
        val edit = store.editRecord(record)
        store.removeItem(edit, requireNotNull(store.openMixedDraft(edit)).items.single().id)
        store.insertPhoto(edit, null, replacement.id)
        store.completeMixedDraft(edit)
        assertFalse(originalFile(oldPhoto.id).exists())
        assertPhotos(store.openRecordContent(record)?.photos, replacement)

        val discardedEdit = store.editRecord(record)
        store.insertPhoto(discardedEdit, null, discarded.id)
        store.discardDraft(discardedEdit)
        assertFalse(originalFile(discarded.id).exists())
        assertPhotos(store.openRecordContent(record)?.photos, replacement)

        val draft = store.appendPhotos(null, listOf(previousUndo.id, latestUndo.id))
        val items = requireNotNull(store.openMixedDraft(draft)).items
        store.removeItem(draft, items.first().id)
        assertArrayEquals(previousUndo.bytes, originalFile(previousUndo.id).readBytes())
        store.removeItem(draft, items.last().id)
        assertFalse(originalFile(previousUndo.id).exists())
        assertArrayEquals(latestUndo.bytes, originalFile(latestUndo.id).readBytes())
        store.discardDraft(draft)
        assertFalse(originalFile(latestUndo.id).exists())
        assertEquals(0, store.retryPhotoCleanup())
        assertArrayEquals(unattached.bytes, originalFile(unattached.id).readBytes())
    }

    @Test
    fun failedRecordDeletionRollsBackDraftUndoAndCleanupBeforeAnyOriginalIsRemoved() = runBlocking {
        val publishedPhoto = importPhoto(Color.BLUE)
        val draftPhoto = importPhoto(Color.RED)
        val undoPhoto = importPhoto(Color.GREEN)
        val record = publishPhoto(publishedPhoto.id)
        val draft = store.editRecord(record)
        store.removeItem(draft, requireNotNull(store.openMixedDraft(draft)).items.single().id)
        store.insertPhoto(draft, null, draftPhoto.id, "未完成照片")
        val removed = store.insertPhoto(draft, null, undoPhoto.id, "可撤销照片")
        val beforeRemoval = requireNotNull(store.openMixedDraft(draft))
        store.removeItem(draft, removed)
        val savedDraft = requireNotNull(store.openMixedDraft(draft))
        store.moveToTrash(record)
        val trashed = requireNotNull(store.openTrashRecord(record))
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER reject_permanent_record_delete BEFORE DELETE ON text_records
               BEGIN SELECT RAISE(ABORT, 'test_reject_permanent_delete'); END""",
        )
        try {
            val failure = runCatching { store.deletePermanently(record) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any {
                it.message?.contains("test_reject_permanent_delete") == true
            })
            assertEquals(trashed, store.openTrashRecord(record))
            assertEquals(0, store.retryPhotoCleanup())
            listOf(publishedPhoto, draftPhoto, undoPhoto).forEach {
                assertArrayEquals(it.bytes, originalFile(it.id).readBytes())
            }
        } finally {
            sql.execSQL("DROP TRIGGER reject_permanent_record_delete")
        }
        reopenDatabase()

        assertEquals(trashed, store.openTrashRecord(record))
        assertTrue(store.restoreRecord(record))
        assertPhotos(store.openRecordContent(record)?.photos, publishedPhoto)
        assertEquals(savedDraft, store.openMixedDraft(draft))
        assertTrue(store.canUndoRemoval(draft))
        assertTrue(store.undoRemoval(draft))
        assertEquals(beforeRemoval.items, store.openMixedDraft(draft)?.items)
        assertPhotos(store.openDraftPhotos(draft), draftPhoto, undoPhoto)
    }

    @Test
    fun fileDeletionFailureLeavesPersistentCleanupThatCanBeRetriedAfterReopen() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val record = publishPhoto(photo.id)
        store.moveToTrash(record)
        val original = originalFile(photo.id)
        val backup = File(original.parentFile, "${original.name}.test-backup")
        val blocker = File(original, "keep")
        check(original.renameTo(backup))
        try {
            check(original.mkdir())
            blocker.writeText("Keep this directory nonempty to reject file deletion.")
            assertEquals(PermanentDeletionResult(deleted = true, pendingPhotoCount = 1), store.deletePermanently(record))
            assertNull(store.openTrashRecord(record))
            assertTrue(store.listTrash().isEmpty())
            reopenDatabase()
            assertEquals(1, store.retryPhotoCleanup())

            check(blocker.delete())
            check(original.delete())
            check(backup.renameTo(original))
            assertEquals(0, store.retryPhotoCleanup())
            assertFalse(original.exists())
            assertEquals(PermanentDeletionResult(deleted = false, pendingPhotoCount = 0), store.deletePermanently(record))
        } finally {
            check(blocker.delete() || !blocker.exists())
            if (original.isDirectory) check(original.delete())
            if (backup.exists()) check(backup.renameTo(original))
        }
    }

    private suspend fun publishPhoto(id: PhotoId): RecordId =
        store.completeMixedDraft(store.appendPhotos(null, listOf(id)))
}
