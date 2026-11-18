package com.baconaa.folio.records

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal class MediaFilterTest : AudioStoreFixture() {
    @Test fun fullPublishedContentDeterminesTypeAndOtherFiltersStillApply() = runBlocking {
        val draft = store.saveDraft(null, TextEdit("河边", 0))!!
        repeat(2) { store.insertText(draft, store.openMixedDraft(draft)!!.items.last().id, TextEdit("片段", 0)) }
        val record = store.completeMixedDraft(draft)
        val edit = store.editRecord(record)
        val photo = importPhoto(Color.BLUE)
        store.appendPhotos(edit, listOf(photo.id))
        val audio = publishAudio(audioClip().id)
        val checklist = store.completeMixedDraft(store.appendChecklist(null, listOf(ChecklistEntry(ChecklistEntryId("a"), "雨伞", false))))
        suspend fun ids(vararg types: MediaFilter) = store.queryRecords(mediaTypes = types.toSet()).map { it.id }.toSet()
        assertEquals(setOf(record), ids(MediaFilter.TEXT_ONLY))
        assertEquals(emptySet<RecordId>(), ids(MediaFilter.PHOTO))
        assertEquals(setOf(checklist), ids(MediaFilter.CHECKLIST))
        store.completeMixedDraft(edit)
        assertEquals(emptySet<RecordId>(), ids(MediaFilter.TEXT_ONLY))
        assertEquals(setOf(record, audio), ids(MediaFilter.PHOTO, MediaFilter.AUDIO))
        val types = setOf(MediaFilter.PHOTO, MediaFilter.AUDIO)
        assertEquals(emptyList<RecordCard>(), store.queryRecords("河边", starredOnly = true, mediaTypes = types))
        store.setStarred(record, true)
        assertEquals(record, store.queryRecords("河边", starredOnly = true, mediaTypes = types).single().id)
        store.moveToTrash(record)
        assertEquals(setOf(audio), ids(MediaFilter.PHOTO, MediaFilter.AUDIO))
        store.restoreRecord(record)
        assertEquals(setOf(record), ids(MediaFilter.PHOTO))
    }
}
