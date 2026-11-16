package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
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
internal class AudioCleanupTest : AudioStoreFixture() {
    @Test
    fun sharedAudioSurvivesFormalTrashHiddenDraftAndRemovalUndoReferences() = runBlocking {
        val shared = audioClip()
        val bytes = audioFile(shared.id).readBytes()
        val active = publishAudio(shared.id)
        val firstDeleted = publishAudio(shared.id)
        store.moveToTrash(firstDeleted)
        assertEquals(PermanentDeletionResult(true, 0, 0), store.deletePermanently(firstDeleted))
        assertArrayEquals(bytes, audioFile(shared.id).readBytes())

        val temporary = store.appendAudio(null, shared.id)
        store.moveToTrash(active)
        store.discardDraft(temporary)
        assertArrayEquals(bytes, audioFile(shared.id).readBytes())
        val ownerDraft = requireNotNull(store.saveDraft(null, TextEdit("没有录音的正式内容", 9)))
        val hiddenOwner = store.completeMixedDraft(ownerDraft)
        val hidden = store.editRecord(hiddenOwner)
        store.appendAudio(hidden, shared.id)
        store.moveToTrash(hiddenOwner)
        store.deletePermanently(active)
        assertNull(store.openMixedDraft(hidden))
        assertTrue(store.openTrashRecord(hiddenOwner)!!.hasDraft)
        assertTrue(store.openTrashRecord(hiddenOwner)!!.content.items.none { it is ContentItem.Audio })
        assertArrayEquals(bytes, audioFile(shared.id).readBytes())

        val draft = store.appendAudio(null, shared.id)
        store.deletePermanently(hiddenOwner)
        assertArrayEquals(bytes, audioFile(shared.id).readBytes())
        store.removeItem(draft, store.openMixedDraft(draft)!!.items.single().id)
        val lastRecord = publishAudio(shared.id)
        store.moveToTrash(lastRecord)
        store.deletePermanently(lastRecord)
        reopenDatabase()
        assertTrue(store.canUndoRemoval(draft))
        assertArrayEquals(bytes, audioFile(shared.id).readBytes())
        assertTrue(store.undoRemoval(draft))
        assertEquals(shared.id, (store.openMixedDraft(draft)!!.items.single() as ContentItem.Audio).audioId)
        store.removeItem(draft, store.openMixedDraft(draft)!!.items.single().id)
        store.discardDraft(draft)
        assertFalse(audioFile(shared.id).exists())
    }

    @Test
    fun nextRemovalReleasesOldUndoWhileUnboundRecordingsRemainUntouched() = runBlocking {
        val previous = audioClip()
        val latest = audioClip()
        val unbound = audioClip()
        val draft = store.appendAudio(null, previous.id)
        store.appendAudio(draft, latest.id)
        val items = store.openMixedDraft(draft)!!.items
        store.removeItem(draft, items[0].id)
        assertTrue(audioFile(previous.id).isFile)
        store.removeItem(draft, items[1].id)
        assertFalse(audioFile(previous.id).exists())
        assertTrue(audioFile(latest.id).isFile)
        assertNull(store.listDrafts().single().totalAudioDurationMillis)
        store.discardDraft(draft)
        assertFalse(audioFile(latest.id).exists())
        assertEquals(0, store.retryAudioCleanup())
        assertTrue(audioFile(unbound.id).isFile)
    }

    @Test
    fun rejectedPermanentDeletionRollsBackAllReferencesAndCleanupCandidates() = runBlocking {
        val published = audioClip()
        val drafted = audioClip()
        val removed = audioClip()
        val record = publishAudio(published.id)
        val draft = store.editRecord(record)
        store.removeItem(draft, store.openMixedDraft(draft)!!.items.single().id)
        store.appendAudio(draft, drafted.id)
        val removedItem = store.insertAudio(draft, null, removed.id)
        store.removeItem(draft, removedItem)
        val saved = store.openMixedDraft(draft)
        store.moveToTrash(record)
        val trashed = store.openTrashRecord(record)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_audio_deletion BEFORE DELETE ON text_records
            BEGIN SELECT RAISE(ABORT, 'test_reject_audio_deletion'); END""")
        try {
            val failure = runCatching { store.deletePermanently(record) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_audio_deletion") == true })
            assertEquals(trashed, store.openTrashRecord(record))
            assertTrue(database.audioCleanupDao().candidates().isEmpty())
            assertEquals(0, store.retryAudioCleanup())
            listOf(published, drafted, removed).forEach { assertTrue(audioFile(it.id).isFile) }
        } finally {
            sql.execSQL("DROP TRIGGER reject_audio_deletion")
        }
        reopenDatabase()
        assertTrue(store.restoreRecord(record))
        assertEquals(saved, store.openMixedDraft(draft))
        assertTrue(store.undoRemoval(draft))
        assertEquals(listOf(drafted.id, removed.id), store.openMixedDraft(draft)!!.items.filterIsInstance<ContentItem.Audio>().map { it.audioId })
        assertTrue(store.moveToTrash(record))
        assertEquals(PermanentDeletionResult(true, 0, 0), store.deletePermanently(record))
        assertEquals(PermanentDeletionResult(false, 0, 0), store.deletePermanently(record))
        assertNull(database.removedItemDao().get(draft.value))
        assertNull(database.textDao().recordEdit(draft.value))
        listOf(published, drafted, removed).forEach { assertFalse(audioFile(it.id).exists()) }
    }

    @Test
    fun failedFileDeletionPersistsAcrossReopenAndRetriesWithoutRevivingRecord() = runBlocking {
        val audio = audioClip()
        val record = publishAudio(audio.id)
        store.moveToTrash(record)
        val original = audioFile(audio.id)
        val backup = File(original.parentFile, "${original.name}.test-backup")
        val blocker = File(original, "keep")
        check(original.renameTo(backup))
        try {
            check(original.mkdir())
            blocker.writeText("Keep directory nonempty to reject deletion.")
            assertEquals(PermanentDeletionResult(true, 0, 1), store.deletePermanently(record))
            assertNull(store.openTrashRecord(record))
            reopenDatabase()
            assertEquals(1, store.retryAudioCleanup())
            check(blocker.delete())
            check(original.delete())
            check(backup.renameTo(original))
            assertEquals(0, store.retryAudioCleanup())
            assertFalse(original.exists())
            assertFalse(store.restoreRecord(record))
        } finally {
            check(blocker.delete() || !blocker.exists())
            if (original.isDirectory) check(original.delete())
            if (backup.exists()) check(backup.renameTo(original))
        }
    }
}
