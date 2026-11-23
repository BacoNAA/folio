package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ChecklistFlowTest : PhotoStoreFixture() {
    private var now = 1_000L

    @Before
    fun useTestClock() {
        store = LocalTextStore(database, InstrumentationRegistry.getInstrumentation().targetContext) { now }
    }

    private fun reopen() {
        reopenDatabase()
        useTestClock()
    }

    private fun entry(id: String, text: String, checked: Boolean = false) =
        ChecklistEntry(ChecklistEntryId(id), text, checked)

    @Test
    fun checklistOnlyDraftRestoresAndEditsPublishTogetherWithoutChangingUneditedTimes() = runBlocking {
        val entries = listOf(entry("tent", "带上\"帐篷\"\n检查🌉", true), entry("water", "水\\茶"))
        val event = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Asia/Shanghai"))
        val draft = store.appendChecklist(null, entries, event)
        val item = store.openMixedDraft(draft)!!.items.single() as ContentItem.Checklist
        assertTrue(store.listDrafts().single().excerpt.contains("帐篷"))
        assertTrue(store.search("帐篷").isEmpty())
        reopen()
        assertEquals(MixedDraft(listOf(item), item.id, 0), store.openMixedDraft(draft))
        assertEquals(entries, (store.openMixedDraft(draft)!!.items.single() as ContentItem.Checklist).entries)
        assertEquals(DraftTimes(1_000L, event), store.openDraftTimes(draft))
        val record = store.completeMixedDraft(draft)
        val original = store.openRecordContent(record)!!
        assertEquals(record, store.search("帐篷").single().id)

        now = 2_000L
        val edit = store.editRecord(record)
        val changed = listOf(entries[1].copy(text = "热水", checked = true), entries[0].copy(checked = false))
        store.editChecklist(edit, item.id, changed)
        reopen()
        assertEquals(original, store.openRecordContent(record))
        assertTrue(store.search("热水").isEmpty())
        assertEquals(changed, (store.openMixedDraft(edit)!!.items.single() as ContentItem.Checklist).entries)
        now = 3_000L
        assertEquals(record, store.completeMixedDraft(edit))
        val published = store.openRecordContent(record)!!
        assertEquals(listOf(item.copy(entries = changed)), published.items)
        assertEquals(RecordTimes(1_000L, 3_000L, event), published.times)
        assertEquals(record, store.search("热水").single().id)

        now = 4_000L
        val unchanged = store.editRecord(record)
        store.editChecklist(unchanged, item.id, entries)
        store.editChecklist(unchanged, item.id, changed)
        assertEquals(record, store.completeMixedDraft(unchanged))
        assertEquals(published, store.openRecordContent(record))
        val discarded = store.editRecord(record)
        store.editChecklist(discarded, item.id, emptyList())
        store.discardDraft(discarded)
        assertNull(store.openMixedDraft(discarded))
        assertEquals(published, store.openRecordContent(record))
    }

    @Test
    fun mixedMovesRemovalAndReadOnlyPreviewKeepChecklistOrderEntriesAndTextCursor() = runBlocking {
        val prefix = "清".repeat(79) + "🌉"
        val entries = listOf(
            entry("one", prefix + "未截断正文", true), entry("two", "雨衣"),
            entry("three", "水壶", true), entry("four", "隐藏的第四条", true),
        )
        val draft = store.appendChecklist(null, entries)
        val checklist = store.openMixedDraft(draft)!!.items.single()
        val photo = importPhoto(Color.BLUE)
        val photoId = store.insertPhoto(draft, null, photo.id, "背包")
        val textId = store.insertText(draft, checklist.id, TextEdit("山🌉间", 3))
        reopen()
        assertEquals(textId, store.openMixedDraft(draft)!!.activeItemId)
        assertEquals(3, store.openMixedDraft(draft)!!.cursor)
        store.moveItem(draft, checklist.id, MoveDirection.DOWN)
        store.removeItem(draft, checklist.id)
        store.editTextItem(draft, textId, TextEdit("雨后山🌉间", 5))
        reopen()
        assertTrue(store.undoRemoval(draft))
        val expected = listOf(ContentItem.Text(textId, "雨后山🌉间"), checklist, ContentItem.Photo(photoId, photo.id, "背包"))
        assertEquals(MixedDraft(expected, checklist.id, 0), store.openMixedDraft(draft))
        val second = store.insertChecklist(draft, photoId, listOf(entry("second", "另一个清单")))
        val record = store.completeMixedDraft(draft)
        val preview = store.listRecentRecords().single().preview
        assertEquals(
            listOf(
                PreviewContentItem.Text(textId, "雨后山🌉间"),
                PreviewContentItem.Checklist(checklist.id,
                    listOf(ChecklistEntryPreview(prefix, true), ChecklistEntryPreview("雨衣", false),
                        ChecklistEntryPreview("水壶", true)), 4, 3),
                PreviewContentItem.Photo(photoId, photo.id, "背包"),
            ), preview.items,
        )
        assertEquals(1, preview.remainingItemCount)
        assertEquals(0, preview.remainingPhotoCount)
        assertEquals(expected + ContentItem.Checklist(second, listOf(entry("second", "另一个清单"))),
            store.openRecordContent(record)!!.items)
        assertEquals(record, store.search("隐藏的第四条").single().id)
    }

    @Test
    fun failedChecklistCompletionRollsBackAndRetryKeepsOneRecord() = runBlocking {
        val entries = listOf(entry("train", "车票"))
        val source = store.appendChecklist(null, entries)
        val record = store.completeMixedDraft(source)
        val original = store.openRecordContent(record)!!
        val draft = store.editRecord(record)
        val item = original.items.single() as ContentItem.Checklist
        now = 2_000L
        store.editChecklist(draft, item.id, listOf(entries.single().copy(checked = true)))
        val saved = store.openMixedDraft(draft)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_checklist_cleanup BEFORE DELETE ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'test_checklist_cleanup'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(draft) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_checklist_cleanup") == true })
            assertEquals(original, store.openRecordContent(record))
            assertEquals(saved, store.openMixedDraft(draft))
        } finally {
            sql.execSQL("DROP TRIGGER reject_checklist_cleanup")
        }
        reopen()
        now = 3_000L
        assertEquals(record, store.completeMixedDraft(draft))
        val published = store.openRecordContent(record)
        assertEquals(saved!!.items, published!!.items)
        assertEquals(3_000L, published.times.modifiedAt)
        reopen()
        now = 4_000L
        assertEquals(record, store.completeMixedDraft(draft))
        assertEquals(published, store.openRecordContent(record))
        assertEquals(listOf(record), store.search("车票").map { it.id })
    }

    @Test
    fun trashRestoreAndPermanentDeletionPreserveBothChecklistVersionsAndRemoval() = runBlocking {
        val entries = listOf(entry("original", "原清单"))
        val record = store.completeMixedDraft(store.appendChecklist(null, entries))
        val original = store.openRecordContent(record)!!
        val item = original.items.single() as ContentItem.Checklist
        val draft = store.editRecord(record)
        val changed = entries.map { it.copy(text = "未完成清单", checked = true) }
        store.editChecklist(draft, item.id, changed)
        store.removeItem(draft, item.id)
        assertTrue(store.moveToTrash(record))
        reopen()
        assertNull(store.openMixedDraft(draft))
        assertTrue(store.listDrafts().isEmpty())
        assertFalse(store.canUndoRemoval(draft))
        assertEquals(original, store.openTrashRecord(record)!!.content)
        assertTrue(store.openTrashRecord(record)!!.hasDraft)
        assertTrue(store.search("原清单").isEmpty())
        assertTrue(store.restoreRecord(record))
        assertEquals(original, store.openRecordContent(record))
        assertTrue(store.undoRemoval(draft))
        assertEquals(listOf(item.copy(entries = changed)), store.openMixedDraft(draft)!!.items)
        assertEquals(record, store.search("原清单").single().id)
        assertTrue(store.search("未完成清单").isEmpty())
        assertTrue(store.moveToTrash(record))
        store.deletePermanently(record)
        assertNull(store.openTrashRecord(record))
        assertNull(store.openMixedDraft(draft))
        assertNull(database.removedItemDao().get(draft.value))
        assertTrue(database.itemDao().draftItems(draft.value).isEmpty())
        assertTrue(database.itemDao().recordItems(record.value).isEmpty())
    }

    @Test
    fun invalidOrBlankChecklistCannotCreateRecordsButClearedDraftRemainsRecoverable() = runBlocking {
        assertTrue(runCatching { store.appendChecklist(null, emptyList()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { store.appendChecklist(null, listOf(entry("blank", " \n\t", true))) }.isFailure)
        val duplicate = listOf(entry("duplicate", "一"), entry("duplicate", "二"))
        assertTrue(runCatching { store.appendChecklist(null, duplicate) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.listDrafts().isEmpty())
        assertTrue(runCatching { ChecklistEntryId(" ") }.isFailure)
        val draft = store.appendChecklist(null, listOf(entry("saved", "已保存")))
        val saved = store.openMixedDraft(draft)!!
        val id = saved.items.single().id
        assertTrue(runCatching { store.editChecklist(draft, id, duplicate) }.isFailure)
        assertEquals(saved, store.openMixedDraft(draft))
        // 旧正文接口不能把清单索引误写成一段文字；无改动完成仍兼容。
        assertTrue(runCatching { store.saveDraft(draft, TextEdit("覆盖清单", 4)) }.isFailure)
        assertEquals(saved, store.openMixedDraft(draft))
        store.editChecklist(draft, id, emptyList())
        reopen()
        assertEquals(listOf(ContentItem.Checklist(id, emptyList())), store.openMixedDraft(draft)!!.items)
        assertTrue(runCatching { store.completeMixedDraft(draft) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(draft, store.listDrafts().single().id)
        assertTrue(store.listRecentRecords().isEmpty())
        store.editChecklist(draft, id, listOf(entry("saved", "再次填写")))
        val record = store.completeDraft(draft, TextEdit("再次填写", 4))
        assertEquals(record, store.search("再次填写").single().id)
    }
}
