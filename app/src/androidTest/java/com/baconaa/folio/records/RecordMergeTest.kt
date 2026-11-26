package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordMergeTest : AudioStoreFixture() {
    @Test fun reorderedMediaAndCluesSurviveReopenAndPublishWithoutChangingEitherSource() = runBlocking {
        var timestamp = 1000L
        store = LocalTextStore(database, InstrumentationRegistry.getInstrumentation().targetContext) { timestamp }
        val zone = ZoneId.of("Asia/Shanghai")
        val clip = audioClip()
        val audioDraft = store.appendAudio(null, clip.id, "开场", EventTime.Year(2023, zone))
        store.saveDraftTitle(audioDraft, "看展录音")
        val tag = store.createDraftTag(audioDraft, "值得再听")
        val person = store.createDraftObject(audioDraft, ObjectKind.PERSON, ObjectInfo("小林"))
        val sound = store.completeMixedDraft(audioDraft)
        val photo = importPhoto(Color.BLUE)
        val photoTime = EventTime.Year(2024, zone)
        val photoDraft = store.appendPhotos(null, listOf(photo.id), photoTime)
        store.saveDraftTitle(photoDraft, "展馆照片")
        val place = store.createDraftPlace(photoDraft, PlaceInfo("展馆"))
        store.createDraftTag(photoDraft, "未选标签")
        store.insertText(photoDraft, null, TextEdit("未选文字", 0))
        val venue = store.completeMixedDraft(photoDraft)
        store.setStarred(venue, true)
        val originals = listOf(sound, venue).associateWith { store.openRecordContent(it)!! }
        val revisions = originals.mapValues { it.value.revision }
        val audioItem = originals.getValue(sound).items.single()
        val photoItem = originals.getValue(venue).items.first()
        val pending = store.editRecord(sound)
        store.editAudioCaption(pending, audioItem.id, "还在编辑的说明")
        val group = RecordGroupId("exhibition")
        store.createGroup(group, "看展", listOf(sound, venue))

        timestamp = 3000L
        val selection = RecordMergeSelection(listOf(MergeItem(venue, photoItem.id), MergeItem(sound, audioItem.id)),
            eventTimeSource = venue, placeId = place, tagIds = setOf(tag), objectIds = setOf(person))
        val draft = store.mergeRecordsToDraft(revisions, selection)
        reopenDatabase()
        val items = store.openMixedDraft(draft)!!.items
        assertEquals(2, items.size)
        assertEquals(photoItem, items.first().withId(photoItem.id))
        assertEquals(audioItem, items.last().withId(audioItem.id))
        assertTrue(items.none { it.id == photoItem.id || it.id == audioItem.id })
        assertEquals(DraftTimes(3000L, photoTime), store.openDraftTimes(draft))
        assertEquals(setOf(sound, venue), store.listRecentRecords().map { it.id }.toSet())

        val merged = store.completeMixedDraft(draft)
        val content = store.openRecordContent(merged)!!
        assertEquals(items, content.items)
        assertPhotos(content.photos, photo)
        assertEquals(clip.durationMillis, audioOriginals.inspect((content.items[1] as ContentItem.Audio).audioId).durationMillis)
        assertEquals(3000L, content.times.firstRecordedAt)
        assertEquals(photoTime, content.times.eventTime)
        assertEquals(listOf(tag), content.tags.map { it.id })
        assertEquals(listOf(person), content.objects.map { it.id })
        assertEquals(place, content.place?.id)
        assertNull(content.title)
        assertEquals(RecordMarks(), content.marks)
        assertEquals(merged, store.completeMixedDraft(draft))
        assertEquals(setOf(sound, venue, merged), store.listRecentRecords().map { it.id }.toSet())
        originals.forEach { (id, before) -> assertEquals(before, store.openRecordContent(id)) }
        assertEquals("还在编辑的说明", (store.openMixedDraft(pending)!!.items.single() as ContentItem.Audio).caption)
        assertEquals(listOf(sound, venue), store.openGroup(group)!!.members.map { it.id })
    }

    @Test fun staleSourcesAndInvalidPairsOrCluesCannotLeavePartialDrafts() = runBlocking {
        val photo = importPhoto(Color.GREEN)
        val photoRecord = store.completeMixedDraft(store.appendPhotos(null, listOf(photo.id)))
        val note = store.completeMixedDraft(store.saveDraft(null, TextEdit("展览说明", 0))!!)
        val original = store.openRecordContent(note)!!
        val picture = store.openRecordContent(photoRecord)!!
        val revisions = mapOf(note to original.revision, photoRecord to picture.revision)
        val textItem = MergeItem(note, original.items.single().id)
        val photoItem = MergeItem(photoRecord, picture.items.single().id)
        val selection = RecordMergeSelection(listOf(photoItem, textItem))
        suspend fun failure(chosen: RecordMergeSelection) =
            runCatching { store.mergeRecordsToDraft(revisions, chosen) }.exceptionOrNull()

        assertTrue(failure(selection.copy(items = listOf(textItem))) is IllegalArgumentException)
        assertTrue(failure(selection.copy(items = listOf(textItem, photoItem, textItem))) is IllegalArgumentException)
        assertTrue(failure(selection.copy(items = listOf(textItem.copy(source = photoRecord), photoItem))) is IllegalArgumentException)
        assertTrue(failure(selection.copy(eventTimeSource = RecordId("unrelated"))) is IllegalArgumentException)
        assertTrue(failure(selection.copy(tagIds = setOf(TagId("unrelated")))) is IllegalArgumentException)
        assertTrue(originalFile(photo.id).delete())
        assertTrue(failure(selection) is CopyMediaUnavailable)
        originalFile(photo.id).writeBytes(photo.bytes)
        val edit = store.editRecord(note)
        store.editTextItem(edit, textItem.itemId, TextEdit("说明已更新", 0))
        store.completeMixedDraft(edit)
        assertTrue(failure(selection) is CopySourceChanged)
        val fresh = revisions + (note to store.openRecordContent(note)!!.revision)
        store.moveToTrash(photoRecord)
        assertTrue(runCatching { store.mergeRecordsToDraft(fresh, selection) }.exceptionOrNull() is CopySourceChanged)
        assertTrue(store.listDrafts().isEmpty())
    }

    @Test fun failedCreationAndFailedCompletionRollBackAndCanBeRetried() = runBlocking {
        val firstDraft = store.saveDraft(null, TextEdit("第一行", 0))!!
        val tag = store.createDraftTag(firstDraft, "看展")
        val first = store.completeMixedDraft(firstDraft)
        val second = store.completeMixedDraft(store.saveDraft(null, TextEdit("第二行", 0))!!)
        val sources = listOf(first, second).associateWith { store.openRecordContent(it)!! }
        val versions = sources.mapValues { it.value.revision }
        val selection = RecordMergeSelection(sources.map { (id, content) -> MergeItem(id, content.items.single().id) }, tagIds = setOf(tag))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_merge BEFORE INSERT ON draft_tags
            BEGIN SELECT RAISE(ABORT, 'reject_merge_tag'); END""")
        try {
            val failure = runCatching { store.mergeRecordsToDraft(versions, selection) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_merge_tag") == true })
            assertTrue(store.listDrafts().isEmpty())
        } finally { sql.execSQL("DROP TRIGGER fail_merge") }
        val draft = store.mergeRecordsToDraft(versions, selection)
        val before = store.openMixedDraft(draft)!!
        sql.execSQL("""CREATE TRIGGER fail_merge_publish BEFORE INSERT ON record_items
            BEGIN SELECT RAISE(ABORT, 'reject_merge_item'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(draft) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_merge_item") == true })
            assertEquals(before, store.openMixedDraft(draft))
            assertEquals(sources.keys, store.listRecentRecords().map { it.id }.toSet())
        } finally { sql.execSQL("DROP TRIGGER fail_merge_publish") }
        reopenDatabase()
        val merged = store.completeMixedDraft(draft)
        assertEquals(merged, store.completeMixedDraft(draft))
        assertEquals(before.items, store.openRecordContent(merged)!!.items)
        assertEquals(sources.keys + merged, store.listRecentRecords().map { it.id }.toSet())
        sources.forEach { (id, beforeMerge) -> assertEquals(beforeMerge, store.openRecordContent(id)) }
    }
}
