package com.baconaa.folio.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordTagFlowTest : PhotoStoreFixture() {
    @Test
    fun privateTagsReopenStayIsolatedAndOnlySelectedTagsBecomeReusable() = runBlocking {
        val draft = textDraft("第一家店")
        val other = textDraft("另一家店")
        val first = store.createDraftTag(draft, " 面馆 ")
        val second = store.createDraftTag(draft, "面馆")
        val foreign = store.createDraftTag(other, "另一份草稿私有")
        assertNotEquals(first, second)
        assertTrue(store.listTags().isEmpty())
        assertTrue(runCatching { store.saveDraftTags(other, listOf(first)) }.isFailure)
        assertTrue(runCatching { store.renameTag(first, "不能共享更名") }.isFailure)
        assertTrue(runCatching { store.renameDraftTag(other, first, "不能跨草稿更名") }.isFailure)
        assertTrue(runCatching { store.saveDraftTags(draft, listOf(first, first)) }.isFailure)
        assertTrue(runCatching { store.createDraftTag(draft, " \n ") }.isFailure)
        store.renameDraftTag(draft, second, "汤面")
        store.saveDraftTags(draft, listOf(second))
        reopenDatabase()
        assertEquals(listOf(Tag(second, "汤面")), store.openDraftTags(draft))
        assertTrue(store.queryRecords("汤面").isEmpty())

        val record = store.completeMixedDraft(draft)
        assertEquals(listOf(Tag(second, "汤面")), store.listTags("汤"))
        assertNull(database.tagDao().tag(first.value))
        assertEquals(listOf(Tag(second, "汤面")), store.openRecordContent(record)!!.tags)
        assertEquals(listOf(Tag(second, "汤面")), store.listRecentRecords().single().tags)
        store.saveDraftTags(other, listOf(second, foreign))
        store.discardDraft(other)
        assertNull(database.tagDao().tag(foreign.value))
        store.moveToTrash(record)
        store.deletePermanently(record)
        assertEquals(listOf(Tag(second, "汤面")), store.listTags())
        val later = textDraft("还会再来")
        store.saveDraftTags(later, listOf(second))
        assertEquals(listOf(Tag(second, "汤面")), store.openDraftTags(later))
    }

    @Test
    fun tagOnlyEditsUpdateRecordButSharedRenameAndNoOpDoNotOverwriteMarksOrTimes() = runBlocking {
        var clock = 100L
        useClock { clock }
        val draft = textDraft("青瓦桥")
        val first = store.createDraftTag(draft, "散步")
        val record = store.completeMixedDraft(draft)
        val original = store.openRecordContent(record)!!
        val edit = store.editRecord(record)
        val second = store.createDraftTag(edit, "下次再来")
        clock = 200
        store.renameTag(first, "河边散步")
        store.setStarred(record, true)
        store.setPinned(record, true)
        assertEquals(original.times, store.openRecordContent(record)!!.times)
        assertEquals(listOf(Tag(first, "河边散步")), store.openRecordContent(record)!!.tags)
        assertEquals(listOf(Tag(first, "河边散步"), Tag(second, "下次再来")), store.openDraftTags(edit))
        assertTrue(store.search("下次再来").isEmpty())
        clock = 300
        store.completeMixedDraft(edit)
        val updated = store.openRecordContent(record)!!
        assertEquals(RecordTimes(100L, 300L, null), updated.times)
        assertEquals(RecordMarks(true, true), updated.marks)
        assertEquals(2L, database.textDao().activeRecord(record.value)!!.revision)

        val unchanged = store.editRecord(record)
        store.renameTag(first, "常走的路")
        clock = 400
        store.saveDraftTags(unchanged, listOf(first, second))
        store.completeMixedDraft(unchanged)
        assertEquals(updated.times, store.openRecordContent(record)!!.times)
        assertEquals(2L, database.textDao().activeRecord(record.value)!!.revision)
        assertEquals("常走的路", store.openRecordContent(record)!!.tags.first().name)
        val reordered = store.editRecord(record)
        store.saveDraftTags(reordered, listOf(second, first))
        clock = 500
        store.completeMixedDraft(reordered)
        assertEquals(500L, store.openRecordContent(record)!!.times.modifiedAt)
        assertEquals(listOf(second, first), store.openRecordContent(record)!!.tags.map { it.id })
        val abandoned = store.editRecord(record)
        store.saveDraftTags(abandoned, emptyList())
        store.discardDraft(abandoned)
        assertEquals(listOf(second, first), store.openRecordContent(record)!!.tags.map { it.id })
        val cleared = store.editRecord(record)
        store.saveDraftTags(cleared, emptyList())
        store.completeMixedDraft(cleared)
        assertTrue(store.openRecordContent(record)!!.tags.isEmpty())
        assertEquals(setOf(first, second), store.listTags().map { it.id }.toSet())
    }

    @Test
    fun rejectedCompletionRollsBackTagPromotionAndRelationshipsUntilIdempotentRetry() = runBlocking {
        val originalDraft = textDraft("帐篷里的雨")
        val first = store.createDraftTag(originalDraft, "露营")
        val record = store.completeMixedDraft(originalDraft)
        val original = store.openRecordContent(record)
        val edit = store.editRecord(record)
        val pending = store.createDraftTag(edit, "雨夜")
        val ignored = store.createDraftTag(edit, "未选新标签")
        store.saveDraftTags(edit, listOf(pending))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_tag_completion BEFORE DELETE ON text_drafts
            BEGIN SELECT RAISE(ABORT, 'test_reject_tag_completion'); END""")
        try {
            val failure = runCatching { store.completeMixedDraft(edit) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_tag_completion") == true })
            assertEquals(original, store.openRecordContent(record))
            assertEquals(edit.value, database.tagDao().tag(pending.value)!!.ownerDraftId)
            assertEquals(listOf(first), store.listTags().map { it.id })
            assertEquals(listOf(Tag(pending, "雨夜")), store.openDraftTags(edit))
        } finally {
            sql.execSQL("DROP TRIGGER reject_tag_completion")
        }
        reopenDatabase()
        assertEquals(record, store.completeMixedDraft(edit))
        assertEquals(record, store.completeMixedDraft(edit))
        assertEquals(listOf(Tag(pending, "雨夜")), store.openRecordContent(record)!!.tags)
        assertNull(database.tagDao().tag(ignored.value))
        assertEquals(setOf(first, pending), store.listTags().map { it.id }.toSet())
        assertEquals(1, store.queryRecords(tagIds = setOf(pending)).size)
    }

    @Test
    fun trashKeepsFormalAndPrivateDraftTagsAndPermanentDeletionOnlyRemovesPrivateTags() = runBlocking {
        val draft = textDraft("旅行")
        val shared = store.createDraftTag(draft, "出游")
        val record = store.completeMixedDraft(draft)
        val edit = store.editRecord(record)
        val pending = store.createDraftTag(edit, "还有计划")
        store.saveDraftTags(edit, listOf(pending))
        store.moveToTrash(record)
        assertNull(store.openDraftTags(edit))
        assertTrue(runCatching { store.createDraftTag(edit, "不能修改") }.isFailure)
        assertTrue(runCatching { store.renameDraftTag(edit, pending, "不能修改") }.isFailure)
        assertTrue(runCatching { store.saveDraftTags(edit, emptyList()) }.isFailure)
        assertTrue(store.queryRecords(tagIds = setOf(shared)).isEmpty())
        store.renameTag(shared, "旧日旅行")
        assertEquals(listOf(Tag(shared, "旧日旅行")), store.openTrashRecord(record)!!.content.tags)
        reopenDatabase()
        store.restoreRecord(record)
        assertEquals(listOf(Tag(pending, "还有计划")), store.openDraftTags(edit))
        assertEquals(listOf(Tag(shared, "旧日旅行")), store.openRecordContent(record)!!.tags)
        store.moveToTrash(record)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER reject_tag_deletion BEFORE DELETE ON text_records
            BEGIN SELECT RAISE(ABORT, 'test_reject_tag_deletion'); END""")
        try {
            assertTrue(runCatching { store.deletePermanently(record) }.isFailure)
            assertEquals(edit.value, database.tagDao().tag(pending.value)!!.ownerDraftId)
            assertTrue(store.openTrashRecord(record)!!.hasDraft)
        } finally {
            sql.execSQL("DROP TRIGGER reject_tag_deletion")
        }
        store.deletePermanently(record)
        assertNull(database.tagDao().tag(pending.value))
        assertTrue(database.tagDao().recordTags(record.value).isEmpty())
        assertTrue(database.tagDao().draftTags(edit.value).isEmpty())
        assertEquals(listOf(Tag(shared, "旧日旅行")), store.listTags())
        assertFalse(store.restoreRecord(record))
        assertNull(store.openDraftTags(edit))
    }

    private suspend fun textDraft(text: String) = requireNotNull(store.saveDraft(null, TextEdit(text, text.length)))

    private fun useClock(now: () -> Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LocalTextStore(database, LocalPhotoOriginals.get(context), now, LocalAudioOriginals.get(context))
    }
}
