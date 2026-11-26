package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordSplitTest : AudioStoreFixture() {
    @Test fun lateWriteFailureKeepsThePlayableOriginalAndReopenedDraftPublishesExactlyOnce() = runBlocking {
        var timestamp = 1000L
        store = LocalTextStore(database, InstrumentationRegistry.getInstrumentation().targetContext) { timestamp }
        val autumn = EventTime.Year(2023, ZoneId.of("Asia/Shanghai"))
        val draft = store.saveDraft(null, TextEdit("雨后山路", 0), autumn)!!
        val photo = importPhoto(Color.GREEN)
        store.insertPhoto(draft, null, photo.id, "溪边")
        val clip = audioClip()
        val sound = store.insertAudio(draft, null, clip.id, "鸟鸣")
        store.saveDraftTitle(draft, "山中游记")
        val tag = store.createDraftTag(draft, "秋游")
        val place = store.createDraftPlace(draft, PlaceInfo("溪谷"))
        val source = store.completeMixedDraft(draft)
        store.setPinned(source, true)
        val companion = store.completeMixedDraft(store.saveDraft(null, TextEdit("车票", 0))!!)
        val group = RecordGroupId("秋日行程")
        val members = listOf(companion, source)
        store.createGroup(group, "秋日行程", members)
        val before = store.openRecordContent(source)!!

        timestamp = 2000L
        val selection = RecordCopySelection(listOf(sound), keepTitle = false, keepEventTime = false, tagIds = setOf(tag))
        val split = store.splitRecordToDraft(source, before.revision, selection)
        val savedDraft = store.openMixedDraft(split)!!
        assertEquals(source, savedDraft.splitSource)
        assertEquals(source, store.listDrafts().single().splitSource)
        assertEquals(before, store.openRecordContent(source))
        // 草稿删除是提交中的最后一步，此处失败须把两条正式内容一起回滚。
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_split_finish BEFORE DELETE ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'reject_split_finish'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(split) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_split_finish") == true })
            assertEquals(before, store.openRecordContent(source))
            assertEquals(savedDraft, store.openMixedDraft(split))
            assertEquals(members.toSet(), store.listRecentRecords().map { it.id }.toSet())
            assertEquals(members, store.openGroup(group)!!.members.map { it.id })
            playsToEnd(store.openRecordContent(source)!!.items.filterIsInstance<ContentItem.Audio>().single().audioId)
        } finally { sql.execSQL("DROP TRIGGER fail_split_finish") }

        reopenDatabase()
        assertEquals(savedDraft, store.openMixedDraft(split))
        val result = store.completeMixedDraft(split)
        reopenDatabase()
        assertEquals(result, store.completeMixedDraft(split))
        assertEquals(members.toSet() + result, store.listRecentRecords().map { it.id }.toSet())
        assertTrue(store.listDrafts().isEmpty())
        val remaining = store.openRecordContent(source)!!
        assertEquals(before.items.filter { it.id != sound }, remaining.items)
        assertEquals(before.title, remaining.title)
        assertEquals(before.times.firstRecordedAt, remaining.times.firstRecordedAt)
        assertEquals(autumn, remaining.times.eventTime)
        assertEquals(before.revision + 1, remaining.revision)
        assertEquals(before.marks, remaining.marks)
        assertEquals(listOf(tag), remaining.tags.map { it.id })
        assertEquals(place, remaining.place?.id)
        assertPhotos(remaining.photos, photo)
        assertEquals(members, store.openGroup(group)!!.members.map { it.id })
        val created = store.openRecordContent(result)!!
        assertEquals(savedDraft.items, created.items)
        assertEquals(clip.id, (created.items.single() as ContentItem.Audio).audioId)
        assertEquals(2000L, created.times.firstRecordedAt)
        assertNull(created.times.eventTime)
        assertNull(created.title)
        assertNull(created.place)
        assertEquals(listOf(tag), created.tags.map { it.id })
        assertEquals(RecordMarks(), created.marks)
    }

    @Test fun splittingAPublishedMergeMovesOnlyTheMergedCopyAfterReopen() = runBlocking {
        val photo = importPhoto(Color.GREEN)
        val clip = audioClip()
        val photoRecord = store.completeMixedDraft(store.appendPhotos(null, listOf(photo.id)))
        val audioRecord = store.completeMixedDraft(store.appendAudio(null, clip.id, "现场录音"))
        val originals = listOf(photoRecord, audioRecord).associateWith { store.openRecordContent(it)!! }
        val selection = RecordMergeSelection(originals.map { (id, content) -> MergeItem(id, content.items.single().id) })
        val merged = store.completeMixedDraft(store.mergeRecordsToDraft(originals.mapValues { it.value.revision }, selection))
        val before = store.openRecordContent(merged)!!
        val (picture, sound) = before.items
        val draft = store.splitRecordToDraft(merged, before.revision, RecordCopySelection(listOf(sound.id)))

        reopenDatabase()
        assertEquals(merged, store.openMixedDraft(draft)!!.splitSource)
        assertEquals(before, store.openRecordContent(merged))
        val separated = store.completeMixedDraft(draft)
        reopenDatabase()
        assertEquals(separated, store.completeMixedDraft(draft))
        val remaining = store.openRecordContent(merged)!!
        assertEquals(listOf(picture), remaining.items)
        assertPhotos(remaining.photos, photo)
        val moved = store.openRecordContent(separated)!!.items.single() as ContentItem.Audio
        assertEquals(sound, moved.withId(sound.id))
        assertEquals(clip.id, moved.audioId)
        playsToEnd(moved.audioId)
        originals.forEach { (id, original) -> assertEquals(original, store.openRecordContent(id)) }
        assertEquals(setOf(photoRecord, audioRecord, merged, separated), store.listRecentRecords().map { it.id }.toSet())
        assertTrue(store.listDrafts().isEmpty())
    }

    @Test fun onlyActualRetainedContentCountsAndFailedDraftCreationLeavesNoLock() = runBlocking {
        val fields = listOf(TemplateField(TemplateFieldType.TEXT, "正文"), TemplateField(TemplateFieldType.AUDIO, "声音"))
        val template = store.saveTemplate(null, "走访", fields, emptySet())
        val draft = store.startTemplateDraft(template.id, template.revision)
        val (text, audio) = store.openMixedDraft(draft)!!.items
        store.saveDraftTitle(draft, "有标题的录音")
        store.fillAudioField(draft, audio.id, audioClip().id)
        val source = store.completeMixedDraft(draft)
        val single = store.openRecordContent(source)!!
        val chooseAudio = RecordCopySelection(listOf(audio.id))
        assertEquals(listOf(audio.id), single.items.map { it.id })
        assertTrue(runCatching { store.splitRecordToDraft(source, single.revision, chooseAudio) }.isFailure)
        assertTrue(store.listDrafts().isEmpty())
        val edit = store.editRecord(source)
        store.editTextItem(edit, text.id, TextEdit("访问记录", 0))
        store.completeMixedDraft(edit)
        val fresh = store.openRecordContent(source)!!
        assertTrue(runCatching { store.splitRecordToDraft(source, single.revision, chooseAudio) }.exceptionOrNull() is CopySourceChanged)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_split_start BEFORE INSERT ON record_split_items
            BEGIN SELECT RAISE(ABORT, 'reject_split_start'); END""")
        try {
            val failure = runCatching { store.splitRecordToDraft(source, fresh.revision, chooseAudio) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_split_start") == true })
            assertTrue(store.listDrafts().isEmpty())
            assertEquals(fresh, store.openRecordContent(source))
        } finally { sql.execSQL("DROP TRIGGER fail_split_start") }
        val opened = store.splitRecordToDraft(source, fresh.revision, chooseAudio)
        store.discardDraft(opened)
        assertTrue(store.listDrafts().isEmpty())
        assertEquals(fresh, store.openRecordContent(source))
    }

    @Test fun removedSelectionsStayInTheSourceEvenWhenNewContentWasAddedToTheDraft() = runBlocking {
        val draft = store.saveDraft(null, TextEdit("原文", 0))!!
        val photo = importPhoto(Color.RED)
        val first = store.insertPhoto(draft, null, photo.id, "正门")
        val second = store.insertPhoto(draft, null, photo.id, "后门")
        val source = store.completeMixedDraft(draft)
        val before = store.openRecordContent(source)!!
        val split = store.splitRecordToDraft(source, before.revision, RecordCopySelection(listOf(second, first)))
        val (back, front) = store.openMixedDraft(split)!!.items
        val added = store.insertText(split, null, TextEdit("补充", 0))
        store.removeItem(split, front.id)
        store.removeItem(split, back.id)
        assertTrue(runCatching { store.completeMixedDraft(split) }.isFailure)
        assertEquals(before, store.openRecordContent(source))
        assertTrue(store.undoRemoval(split))
        val record = store.completeMixedDraft(split)
        assertEquals(before.items.filter { it.id != second }, store.openRecordContent(source)!!.items)
        val newItems = store.openRecordContent(record)!!.items
        assertEquals(listOf(back, ContentItem.Text(added, "补充")), newItems)
        assertEquals("后门", (back as ContentItem.Photo).caption)
        assertPhotos(store.openRecordContent(record)!!.photos, photo)
    }

    @Test fun editConflictsTrashRestoreAndPermanentDeletionRespectThePendingSplit() = runBlocking {
        val draft = store.saveDraft(null, TextEdit("游记", 0))!!
        val photo = importPhoto(Color.YELLOW)
        val image = store.insertPhoto(draft, null, photo.id, "门票")
        val source = store.completeMixedDraft(draft)
        val original = store.openRecordContent(source)!!
        val selection = RecordCopySelection(listOf(image))
        suspend fun split() = store.splitRecordToDraft(source, original.revision, selection)
        val edit = store.editRecord(source)
        store.editPhotoCaption(edit, image, "未完成的备注")
        assertTrue(runCatching { split() }.exceptionOrNull() is SplitDraftConflict)
        assertEquals("未完成的备注", (store.openMixedDraft(edit)!!.items.last() as ContentItem.Photo).caption)
        store.discardDraft(edit)
        val active = split()
        assertTrue(runCatching { store.editRecord(source) }.exceptionOrNull() is SplitDraftConflict)
        assertTrue(runCatching { split() }.exceptionOrNull() is SplitDraftConflict)
        val splitContent = store.openMixedDraft(active)!!
        store.moveToTrash(source)
        assertTrue(store.listDrafts().isEmpty())
        assertNull(store.openMixedDraft(active))
        val trash = store.openTrashRecord(source)!!
        assertTrue(trash.hasDraft)
        assertEquals(original, trash.content)
        assertTrue(runCatching { store.completeMixedDraft(active) }.isFailure)
        assertTrue(store.restoreRecord(source))
        assertEquals(splitContent, store.openMixedDraft(active))
        val extra = importPhoto(Color.BLUE)
        store.insertPhoto(active, null, extra.id, "只在拆分草稿里")
        store.moveToTrash(source)
        assertTrue(store.deletePermanently(source).deleted)
        assertTrue(store.listDrafts().isEmpty())
        assertNull(store.openMixedDraft(active))
        assertFalse(originalFile(photo.id).exists())
        assertFalse(originalFile(extra.id).exists())
    }
}
