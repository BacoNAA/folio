package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class PhotoRecordFlowTest : PhotoStoreFixture() {
    @Test
    fun completingAnEditPublishesBothOriginalsInOrderOnTheSameRecord() = runBlocking {
        val body = TextEdit("旧桥", 2)
        val created = requireNotNull(store.saveDraft(null, body))
        val record = store.completeDraft(created, body)
        val red = importPhoto(Color.RED)
        val blue = importPhoto(Color.BLUE)
        val editing = store.editRecord(record)
        store.appendPhotos(editing, listOf(red.id))
        store.appendPhotos(editing, listOf(blue.id))
        reopenDatabase()

        assertPhotos(store.openDraftPhotos(editing), red, blue)
        assertPhotos(store.openRecordContent(record)?.photos)
        assertNull(store.search("旧桥").single().firstPhotoId)
        val initialRevision = requireNotNull(database.textDao().activeRecord(record.value)).revision
        assertEquals(record, store.completeDraft(editing, body))
        reopenDatabase()

        assertNull(store.openDraftPhotos(editing))
        assertEquals(record, store.completeDraft(editing, body))
        assertEquals(initialRevision + 1, requireNotNull(database.textDao().activeRecord(record.value)).revision)
        assertEquals(red.id, store.search("旧桥").single().firstPhotoId)
        val content = requireNotNull(store.openRecordContent(record))
        assertEquals(body.text, content.text)
        assertPhotos(content.photos, red, blue)
    }

    @Test
    fun photoOnlyRecordKeepsItsOriginalWhenNextEditIsDiscarded() = runBlocking {
        val red = importPhoto(Color.RED)
        val created = store.appendPhotos(null, listOf(red.id))
        val record = store.completeDraft(created, TextEdit("", 0))
        val editing = store.editRecord(record)
        val blue = importPhoto(Color.BLUE)
        store.appendPhotos(editing, listOf(blue.id))
        assertPhotos(store.openDraftPhotos(editing), red, blue)
        assertPhotos(store.openRecordContent(record)?.photos, red)

        store.discardDraft(editing)
        assertNull(store.openDraftPhotos(editing))
        assertPhotos(store.openRecordContent(record)?.photos, red)
    }

    @Test
    fun photoWriteFailureRollsBackTextAndKeepsBothVersionsOfThePhotos() = runBlocking {
        val red = importPhoto(Color.RED)
        val blue = importPhoto(Color.BLUE)
        val created = store.appendPhotos(null, listOf(red.id))
        val record = store.completeDraft(created, TextEdit("旧桥", 2))
        val editing = store.editRecord(record)
        val updated = TextEdit("新桥", 2)
        store.saveDraft(editing, updated)
        store.appendPhotos(editing, listOf(blue.id))

        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER reject_photo BEFORE INSERT ON record_photos
               BEGIN SELECT RAISE(ABORT, 'reject_photo'); END""",
        )
        val failure = runCatching { store.completeDraft(editing, updated) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_photo") == true })
        val published = requireNotNull(store.openRecordContent(record))
        assertEquals("旧桥", published.text)
        assertPhotos(published.photos, red)
        assertEquals(updated, store.openDraft(editing))
        assertPhotos(store.openDraftPhotos(editing), red, blue)
    }
}
