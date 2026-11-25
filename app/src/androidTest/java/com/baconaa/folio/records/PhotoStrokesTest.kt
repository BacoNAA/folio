package com.baconaa.folio.records

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class PhotoStrokesTest : PhotoStoreFixture() {
    private val switch = PhotoStroke(listOf(PhotoPoint(0f, 0.2f), PhotoPoint(0.2f, 0.4f), PhotoPoint(0f, 0.2f)))
    private val fuse = PhotoStroke(listOf(PhotoPoint(1f, 0.8f)))

    @Test fun sharedOriginalKeepsEachOccurrenceAndSurvivesDeletingOneRecord() = runBlocking {
        val image = importPhoto(Color.GRAY)
        val firstDraft = store.appendPhotos(null, listOf(image.id, image.id))
        val photos = store.openMixedDraft(firstDraft)!!.items.filterIsInstance<ContentItem.Photo>()
        val marked = store.editPhotoStrokes(firstDraft, photos.first().id, listOf(switch))
        assertEquals(photos.first().copy(strokes = listOf(switch)), marked)
        val first = store.completeMixedDraft(firstDraft)

        val secondDraft = store.appendPhotos(null, listOf(image.id))
        val secondItem = store.openMixedDraft(secondDraft)!!.items.single()
        val other = store.editPhotoStrokes(secondDraft, secondItem.id, listOf(fuse))
        assertEquals(listOf(fuse), other.strokes)
        val second = store.completeMixedDraft(secondDraft)
        reopenDatabase()

        assertEquals(listOf(marked, photos.last()), store.openRecordContent(first)!!.items)
        assertTrue(store.moveToTrash(first))
        assertEquals(listOf(marked, photos.last()), store.openTrashRecord(first)!!.content.items)
        assertTrue(store.deletePermanently(first).deleted)
        val remaining = store.openRecordContent(second)!!
        assertEquals(listOf(other), remaining.items)
        assertPhotos(remaining.photos, image)
    }

    @Test fun undoClearAndDiscardRespectDraftAndPublishedVersions() = runBlocking {
        val image = importPhoto(Color.CYAN)
        val draft = store.appendPhotos(null, listOf(image.id))
        val item = store.openMixedDraft(draft)!!.items.single().id
        val both = store.editPhotoStrokes(draft, item, listOf(switch, fuse))
        val record = store.completeMixedDraft(draft)
        val revision = database.textDao().activeRecord(record.value)!!.revision
        val unchanged = store.editRecord(record)
        store.editPhotoStrokes(unchanged, item, both.strokes)
        store.completeMixedDraft(unchanged)
        assertEquals(revision, database.textDao().activeRecord(record.value)!!.revision)
        val edit = store.editRecord(record)
        val one = store.editPhotoStrokes(edit, item, both.strokes.dropLast(1))
        assertEquals(listOf(switch), one.strokes)
        store.removeItem(edit, item)
        reopenDatabase()
        assertTrue(store.undoRemoval(edit))
        assertEquals(one, store.openMixedDraft(edit)!!.items.single())
        assertEquals(both, store.openRecordContent(record)!!.items.single())

        store.moveToTrash(record)
        assertTrue(runCatching { store.editPhotoStrokes(edit, item, emptyList()) }.isFailure)
        store.restoreRecord(record)
        assertEquals(one, store.openMixedDraft(edit)!!.items.single())
        store.discardDraft(edit)
        assertEquals(both, store.openRecordContent(record)!!.items.single())
        assertEquals(revision, database.textDao().activeRecord(record.value)!!.revision)

        val next = store.editRecord(record)
        val clear = store.editPhotoStrokes(next, item, emptyList())
        assertEquals(emptyList<PhotoStroke>(), clear.strokes)
        assertEquals(record, store.completeMixedDraft(next))
        assertEquals(clear, store.openRecordContent(record)!!.items.single())
        assertEquals(revision + 1, database.textDao().activeRecord(record.value)!!.revision)
        assertPhotos(store.openRecordContent(record)!!.photos, image)
    }

    @Test fun failedWriteCanRetryWithoutLosingCaptionCursorOrOriginal() = runBlocking {
        val image = importPhoto(Color.YELLOW)
        val text = TextEdit("房间", 1)
        val draft = store.saveDraft(null, text)!!
        val paragraph = store.openMixedDraft(draft)!!.items.single().id
        val photo = store.insertPhoto(draft, paragraph, image.id, "配电箱")
        store.editTextItem(draft, paragraph, text)
        val before = store.openMixedDraft(draft)!!
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_annotation_time BEFORE UPDATE OF lastEditedAt ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'reject_annotation_time'); END""")
        val failure = runCatching { store.editPhotoStrokes(draft, photo, listOf(switch)) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_annotation_time") == true })
        assertEquals(before, store.openMixedDraft(draft))
        assertPhotos(store.openDraftPhotos(draft), image)
        sql.execSQL("DROP TRIGGER reject_annotation_time")

        assertTrue(runCatching { store.editPhotoStrokes(draft, paragraph, listOf(switch)) }.isFailure)
        val saved = store.editPhotoStrokes(draft, photo, listOf(switch))
        assertEquals(ContentItem.Photo(photo, image.id, "配电箱", listOf(switch)), saved)
        assertEquals(before.copy(items = listOf(before.items.first(), saved)), store.openMixedDraft(draft))
        val record = store.completeMixedDraft(draft)
        assertEquals(saved, store.openRecordContent(record)!!.items.last())
    }

    @Test fun longStrokeCanBeReopenedAndOversizedEditsKeepLastSave() = runBlocking {
        val image = importPhoto(Color.BLUE)
        val draft = store.appendPhotos(null, listOf(image.id))
        val item = store.openMixedDraft(draft)!!.items.single().id
        val point = PhotoPoint(0.12345678f, 0.9876543f)
        val large = PhotoStroke(List(70_000) { point })
        val saved = store.editPhotoStrokes(draft, item, listOf(large))
        assertEquals(listOf(large), saved.strokes)
        val failure = runCatching { store.editPhotoStrokes(draft, item, List(4) { large }) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && failure.message.orEmpty().contains("减少笔画"))
        reopenDatabase()
        assertEquals(saved, store.openMixedDraft(draft)!!.items.single())
        assertPhotos(store.openDraftPhotos(draft), image)
    }

    @Test fun editingACaptionCannotLeaveAnAnnotatedPhotoUnreadable() = runBlocking {
        val image = importPhoto(Color.RED)
        val draft = store.appendPhotos(null, listOf(image.id))
        val item = store.openMixedDraft(draft)!!.items.single().id
        val stroke = PhotoStroke(List(70_000) { PhotoPoint(0.5f, 0.5f) })
        val saved = store.editPhotoStrokes(draft, item, listOf(stroke))
        val caption = "a".repeat(1_600_000)
        val change = runCatching { store.editPhotoCaption(draft, item, caption) }
        reopenDatabase()
        val actual = store.openMixedDraft(draft)!!.items.single() as ContentItem.Photo
        if (change.isSuccess) {
            assertEquals(caption.length, actual.caption.length)
            assertEquals(saved.strokes, actual.strokes)
        } else {
            assertTrue(change.exceptionOrNull()?.message.orEmpty().contains("照片说明"))
            assertEquals(saved, actual)
        }
    }
}
