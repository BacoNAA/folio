package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

internal class RecordCopyTest : PhotoStoreFixture() {
    @Test fun selectedContentHasNewIdentitiesAndOnlyChosenClues() = runBlocking {
        var timestamp = 1000L
        store = LocalTextStore(database, InstrumentationRegistry.getInstrumentation().targetContext) { timestamp }
        val season = EventTime.Year(2022, ZoneId.of("Asia/Shanghai"))
        val entry = ChecklistEntry(ChecklistEntryId.create(), "帐篷", true)
        val draft = store.appendChecklist(null, listOf(entry), season)
        store.saveDraftTitle(draft, "春游装备")
        val picture = importPhoto(Color.CYAN)
        val photo = store.insertPhoto(draft, null, picture.id, "营地")
        val strokes = listOf(PhotoStroke(listOf(PhotoPoint(0.4f, 0.7f))))
        store.editPhotoStrokes(draft, photo, strokes)
        val text = store.insertText(draft, null, TextEdit("原路线", 0))
        val place = store.createDraftPlace(draft, PlaceInfo("青山", city = "成都"))
        val tag = store.createDraftTag(draft, "春天")
        store.createDraftTag(draft, "旧清单")
        store.createDraftObject(draft, ObjectKind.PERSON, ObjectInfo("阿林", note = "队友"))
        val guide = store.createDraftObject(draft, ObjectKind.PERSON, ObjectInfo("阿林", note = "向导"))
        val source = store.completeMixedDraft(draft)
        store.setStarred(source, true)
        store.setPinned(source, true)
        val group = RecordGroupId("hikes")
        store.createGroup(group, "出游", listOf(source))
        val original = store.openRecordContent(source)!!
        val checklist = original.items.filterIsInstance<ContentItem.Checklist>().single()
        val pending = store.editRecord(source)
        store.editTextItem(pending, text, TextEdit("未完成的新路线", 0))
        store.editPhotoCaption(pending, photo, "未完成的说明")

        timestamp = 9000L
        val selection = RecordCopySelection(listOf(photo, checklist.id), keepChecked = false,
            placeId = place, tagIds = setOf(tag), objectIds = setOf(guide))
        val copy = store.copyRecordToDraft(source, original.revision, selection)
        reopenDatabase()
        val contents = store.openMixedDraft(copy)!!
        val copiedPhoto = contents.items[0] as ContentItem.Photo
        val copiedList = contents.items[1] as ContentItem.Checklist
        assertEquals(2, contents.items.size)
        assertEquals(ContentItem.Photo(copiedPhoto.id, picture.id, "营地", strokes), copiedPhoto)
        assertTrue(contents.items.none { item -> original.items.any { it.id == item.id } })
        val copiedEntry = copiedList.entries.single()
        assertNotEquals(entry.id, copiedEntry.id)
        assertEquals(entry.copy(id = copiedEntry.id, checked = false), copiedEntry)
        assertEquals(DraftTimes(9000L, season), store.openDraftTimes(copy))
        assertEquals(listOf(source), store.search("春游装备").map { it.id })
        assertNull(store.listDrafts().single { it.id == copy }.recordId)
        assertEquals(listOf(tag), store.openDraftTags(copy)!!.map { it.id })
        assertEquals(listOf(guide), store.openDraftObjects(copy)!!.map { it.id })
        assertEquals(place, store.openDraftPlace(copy)?.id)

        val published = store.completeMixedDraft(copy)
        assertEquals(published, store.completeMixedDraft(copy))
        assertNotEquals(source, published)
        assertEquals(RecordMarks(), store.openRecordContent(published)!!.marks)
        assertEquals(9000L, store.openRecordContent(published)!!.times.firstRecordedAt)
        assertEquals(original, store.openRecordContent(source))
        assertEquals("未完成的新路线", (store.openMixedDraft(pending)!!.items.last() as ContentItem.Text).text)
        assertEquals(listOf(source), store.openGroup(group)!!.members.map { it.id })
        val another = store.copyRecordToDraft(source, original.revision,
            RecordCopySelection(listOf(checklist.id), keepTitle = false, keepEventTime = false))
        assertNull(store.openDraftTimes(another)!!.eventTime)
        assertNull(store.openMixedDraft(another)!!.title)
        assertTrue((store.openMixedDraft(another)!!.items.single() as ContentItem.Checklist).entries.single().checked)
        store.discardDraft(another)
        assertEquals(setOf(source, published), store.search("春游装备").map { it.id }.toSet())
        store.moveToTrash(source)
        assertTrue(store.deletePermanently(source).deleted)
        assertPhotos(store.openRecordContent(published)!!.photos, picture)
    }

    @Test fun failedCopyRollsBackAndStaleOrUnrelatedSelectionsCannotCreateDrafts() = runBlocking {
        val picture = importPhoto(Color.YELLOW)
        val draft = store.appendPhotos(null, listOf(picture.id))
        val paragraph = store.insertText(draft, null, TextEdit("原路线", 0))
        val tag = store.createDraftTag(draft, "路线")
        val source = store.completeMixedDraft(draft)
        val initial = store.openRecordContent(source)!!
        val selection = RecordCopySelection(initial.items.map { it.id }, tagIds = setOf(tag))
        suspend fun attempt(options: RecordCopySelection = selection) =
            runCatching { store.copyRecordToDraft(source, initial.revision, options) }

        assertTrue(attempt(selection.copy(itemIds = listOf(paragraph, paragraph))).exceptionOrNull() is IllegalArgumentException)
        assertTrue(attempt(selection.copy(itemIds = listOf(ItemId("elsewhere")))).exceptionOrNull() is IllegalArgumentException)
        assertTrue(attempt(selection.copy(objectIds = setOf(ObjectId("elsewhere")))).exceptionOrNull() is IllegalArgumentException)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_copy BEFORE INSERT ON draft_tags
            BEGIN SELECT RAISE(ABORT, 'reject_copy_tag'); END""")
        try {
            val failure = attempt().exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("reject_copy_tag") == true })
            assertTrue(store.listDrafts().isEmpty())
            assertEquals(initial, store.openRecordContent(source))
        } finally { sql.execSQL("DROP TRIGGER fail_copy") }
        val copy = attempt().getOrThrow()
        assertEquals(listOf(copy), store.listDrafts().map { it.id })
        store.discardDraft(copy)
        assertTrue(originalFile(picture.id).delete())
        assertTrue(attempt().exceptionOrNull() is CopyMediaUnavailable)
        assertTrue(store.listDrafts().isEmpty())
        val textOnly = attempt(selection.copy(itemIds = listOf(paragraph))).getOrThrow()
        assertEquals("原路线", store.openDraft(textOnly)?.text)
        store.discardDraft(textOnly)

        originalFile(picture.id).writeBytes(picture.bytes)
        val edit = store.editRecord(source)
        store.editTextItem(edit, paragraph, TextEdit("新路线", 0))
        store.completeMixedDraft(edit)
        assertTrue(attempt().exceptionOrNull() is CopySourceChanged)
        store.moveToTrash(source)
        val failure = runCatching { store.copyRecordToDraft(source, initial.revision + 1, selection) }.exceptionOrNull()
        assertTrue(failure is CopySourceChanged)
        assertTrue(store.listDrafts().isEmpty())
    }
}
