package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baconaa.folio.media.AudioId
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class AudioRecordFlowTest : AudioStoreFixture() {
    @Test
    fun pureAudioDraftReopensWithDurationAndPublishesOnce() = runBlocking {
        val first = audioClip()
        val second = audioClip()
        val event = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Asia/Shanghai"))
        val draft = store.appendAudio(null, first.id, "夜晚虫鸣", event)
        val firstItem = store.openMixedDraft(draft)!!.items.single() as ContentItem.Audio
        assertEquals(first.durationMillis, firstItem.durationMillis)
        assertEquals(draft, store.appendAudio(draft, second.id))
        val saved = store.openMixedDraft(draft)
        val bytes = audioFile(first.id).readBytes()
        assertTrue(store.listRecentRecords().isEmpty())
        assertTrue(store.search("").isEmpty())
        reopenDatabase()

        assertEquals(saved, store.openMixedDraft(draft))
        val preview = store.listDrafts().single()
        assertEquals("", preview.excerpt)
        assertNull(preview.firstPhotoId)
        assertEquals(first.durationMillis + second.durationMillis, preview.totalAudioDurationMillis)
        assertEquals(event, store.openDraftTimes(draft)!!.eventTime)
        assertArrayEquals(bytes, audioOriginals.openOriginal(first.id).use { it.readBytes() })
        val record = store.completeMixedDraft(draft)
        assertEquals(saved!!.items, store.openRecordContent(record)!!.items)
        assertTrue(store.listDrafts().isEmpty())
        reopenDatabase()
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(1, store.listRecentRecords().size)
        val card = store.listRecentRecords().single()
        assertEquals(event, card.eventTime)
        assertEquals(PreviewContentItem.Audio(firstItem.id, first.id, first.durationMillis, "夜晚虫鸣"), card.preview.items[0])
        assertEquals(0, card.preview.remainingAudioCount)
    }

    @Test
    fun allFourTypesKeepOrderCaptionsAndUndoAcrossReopenAndPreviewRemainingAudio() = runBlocking {
        val audio = audioClip()
        val photo = importPhoto(Color.BLUE)
        val draft = requireNotNull(store.saveDraft(null, TextEdit("桥边", 2)))
        val text = store.openMixedDraft(draft)!!.items.single()
        val photoItem = store.insertPhoto(draft, null, photo.id, "蓝桥")
        val checklist = store.insertChecklist(draft, null, listOf(ChecklistEntry(ChecklistEntryId("water"), "带水", true)))
        val audioItem = store.insertAudio(draft, text.id, audio.id, "虫鸣")
        store.editAudioCaption(draft, audioItem, "夜晚虫鸣")
        store.appendAudio(draft, audio.id, "另一处声音说明")
        val last = store.openMixedDraft(draft)!!.items.last().id
        assertEquals(listOf(text.id, audioItem, photoItem, checklist, last), store.openMixedDraft(draft)!!.items.map { it.id })
        store.moveItem(draft, audioItem, MoveDirection.DOWN)
        store.moveItem(draft, audioItem, MoveDirection.UP)
        val saved = store.openMixedDraft(draft)!!
        store.removeItem(draft, audioItem)
        reopenDatabase()
        assertTrue(store.canUndoRemoval(draft))
        assertTrue(store.undoRemoval(draft))
        assertEquals(saved.items, store.openMixedDraft(draft)!!.items)
        assertEquals(audioItem, store.openMixedDraft(draft)!!.activeItemId)
        assertEquals(0, store.openMixedDraft(draft)!!.cursor)

        val record = store.completeMixedDraft(draft)
        val preview = store.listRecentRecords().single().preview
        assertEquals(listOf(text.id, audioItem, photoItem), preview.items.map { it.id })
        assertEquals(PreviewContentItem.Audio(audioItem, audio.id, audio.durationMillis, "夜晚虫鸣"), preview.items[1])
        assertEquals(2, preview.remainingItemCount)
        assertEquals(0, preview.remainingPhotoCount)
        assertEquals(1, preview.remainingAudioCount)
        val edit = store.editRecord(record)
        store.editAudioCaption(edit, audioItem, "草稿说明")
        assertEquals(preview, store.listRecentRecords().single().preview)
        store.discardDraft(edit)
        assertEquals(saved.items, store.openRecordContent(record)!!.items)
        assertPhotos(store.openRecordContent(record)!!.photos, photo)
    }

    @Test
    fun failedCompletionPreservesPublishedAudioAndDraftUntilRetry() = runBlocking {
        val old = audioClip()
        val replacement = audioClip()
        val record = publishAudio(old.id)
        val published = store.openRecordContent(record)
        val draft = store.editRecord(record)
        store.removeItem(draft, store.openMixedDraft(draft)!!.items.single().id)
        store.appendAudio(draft, replacement.id, "新声音")
        val edited = store.openMixedDraft(draft)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_audio_completion BEFORE DELETE ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'test_reject_audio_completion'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(draft) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_audio_completion") == true })
            assertEquals(published, store.openRecordContent(record))
            assertEquals(edited, store.openMixedDraft(draft))
            assertTrue(store.canUndoRemoval(draft))
            assertTrue(audioFile(old.id).isFile)
            assertTrue(audioFile(replacement.id).isFile)
            assertTrue(database.audioCleanupDao().candidates().isEmpty())
        } finally {
            sql.execSQL("DROP TRIGGER reject_audio_completion")
        }
        reopenDatabase()
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(edited!!.items, store.openRecordContent(record)!!.items)
        assertFalse(audioFile(old.id).exists())
        assertTrue(audioFile(replacement.id).isFile)
        val discarded = audioClip()
        val next = store.editRecord(record)
        store.appendAudio(next, discarded.id)
        store.discardDraft(next)
        assertFalse(audioFile(discarded.id).exists())
        assertEquals(edited.items, store.openRecordContent(record)!!.items)
    }

    @Test
    fun missingOrDamagedOriginalCannotCreateDraftOrReplaceSavedVersion() = runBlocking {
        val missing = AudioId(UUID.randomUUID())
        assertTrue(runCatching { store.appendAudio(null, missing) }.isFailure)
        assertTrue(store.listDrafts().isEmpty())
        val audio = audioClip()
        val draft = store.appendAudio(null, audio.id)
        val saved = store.openMixedDraft(draft)
        assertTrue(runCatching { store.insertAudio(draft, null, missing) }.isFailure)
        assertEquals(saved, store.openMixedDraft(draft))
        val bytes = audioFile(audio.id).readBytes()
        audioFile(audio.id).writeText("incomplete recording")
        assertTrue(runCatching { store.completeMixedDraft(draft) }.isFailure)
        assertEquals(saved, store.openMixedDraft(draft))
        assertTrue(store.listRecentRecords().isEmpty())
        audioFile(audio.id).writeBytes(bytes)
        assertEquals(saved!!.items, store.openRecordContent(store.completeMixedDraft(draft))!!.items)
    }

    @Test
    fun trashHidesAudioEditsAndRestoresBothVersionsAndRemovalUndo() = runBlocking {
        val old = audioClip()
        val replacement = audioClip()
        val record = publishAudio(old.id)
        val published = store.openRecordContent(record)!!
        val draft = store.editRecord(record)
        store.removeItem(draft, published.items.single().id)
        val added = store.insertAudio(draft, null, replacement.id, "草稿录音")
        val edited = store.openMixedDraft(draft)
        assertTrue(store.moveToTrash(record))
        assertEquals(published, store.openTrashRecord(record)!!.content)
        assertTrue(store.openTrashRecord(record)!!.hasDraft)
        assertNull(store.openMixedDraft(draft))
        assertTrue(store.listDrafts().isEmpty())
        assertTrue(store.listRecentRecords().isEmpty())
        assertTrue(runCatching { store.editAudioCaption(draft, added, "不应保存") }.isFailure)
        assertFalse(store.undoRemoval(draft))
        store.discardDraft(draft)
        reopenDatabase()
        assertTrue(store.restoreRecord(record))
        assertEquals(published, store.openRecordContent(record))
        assertEquals(edited, store.openMixedDraft(draft))
        assertTrue(store.undoRemoval(draft))
        assertEquals(listOf(old.id, replacement.id), store.openMixedDraft(draft)!!.items.filterIsInstance<ContentItem.Audio>().map { it.audioId })
        assertEquals(record, store.completeMixedDraft(draft))
    }
}
