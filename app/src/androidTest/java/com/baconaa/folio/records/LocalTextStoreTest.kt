package com.baconaa.folio.records

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTextStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "draft-test-${UUID.randomUUID()}.db"
    private lateinit var database: FolioDatabase
    private lateinit var store: LocalTextStore
    private var millis = 1_000L

    @Before
    fun openDatabase() {
        database = Room.databaseBuilder(context, FolioDatabase::class.java, databaseName).build()
        store = LocalTextStore(database, now = { millis })
    }

    @After
    fun cleanUp() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    @Test
    fun blankNewEditDoesNotLeaveADraft() = runBlocking {
        assertNull(store.saveDraft(null, TextEdit("", 0)))
        assertNull(store.saveDraft(null, TextEdit(" \n", 2)))
        assertTrue(store.listDrafts().isEmpty())
    }

    @Test
    fun oneCharacterAndLaterEmojiEditKeepTheirCursorAfterReopen() = runBlocking {
        val initial = TextEdit("桥", 1)
        val id = requireNotNull(store.saveDraft(null, initial))
        reopenDatabase()
        assertEquals(initial, store.openDraft(id))

        val continued = TextEdit("桥🌉边", 3)
        assertEquals(id, store.saveDraft(id, continued))
        reopenDatabase()
        assertEquals(continued, store.openDraft(id))
        assertEquals(listOf(id), store.listDrafts().map { it.id })
    }

    @Test
    fun editingOlderDraftMovesItFirstAndPreservesTheOtherDraft() = runBlocking {
        val first = requireNotNull(store.saveDraft(null, TextEdit("早", 1)))
        millis += 100
        val otherEdit = TextEdit("晚霞", 0)
        val other = requireNotNull(store.saveDraft(null, otherEdit))
        assertEquals(listOf(other, first), store.listDrafts().map { it.id })

        millis += 100
        val revised = TextEdit("早安", 2)
        assertEquals(first, store.saveDraft(first, revised))
        assertEquals(
            listOf(DraftPreview(first, revised.text), DraftPreview(other, otherEdit.text)),
            store.listDrafts(),
        )
        assertEquals(revised, store.openDraft(first))
        assertEquals(otherEdit, store.openDraft(other))
    }

    @Test
    fun updatingAnUnknownIdFailsWithoutCreatingADraft() = runBlocking {
        val missing = DraftId("never-saved")
        val failure = runCatching { store.saveDraft(missing, TextEdit("桥", 1)) }.exceptionOrNull()

        assertTrue("未知草稿 ID 更新应失败", failure is IllegalStateException)
        assertNull(store.openDraft(missing))
        assertTrue(store.listDrafts().isEmpty())
    }

    @Test
    fun completionKeepsFinalInputAndFirstRecordedTimeAcrossRetries() = runBlocking {
        val firstRecordedAt = millis
        val draftId = requireNotNull(store.saveDraft(null, TextEdit("青", 1)))
        millis += 100
        store.saveDraft(draftId, TextEdit("青瓦", 2))
        millis += 100
        val committedAt = millis
        val finalEdit = TextEdit("青瓦桥", 3)
        val recordId = store.completeDraft(draftId, finalEdit)
        assertNull(store.openDraft(draftId))

        millis += 100
        assertEquals(recordId, store.completeDraft(draftId, finalEdit))
        reopenDatabase()
        assertEquals(recordId, store.completeDraft(draftId, finalEdit))
        val lateSave = runCatching { store.saveDraft(draftId, TextEdit("青", 1)) }
        assertTrue(lateSave.exceptionOrNull() is IllegalStateException)
        assertTrue(store.listDrafts().isEmpty())

        val sql = database.openHelper.readableDatabase
        sql.query("SELECT id, sourceDraftId, body, firstRecordedAt, modifiedAt, deletedAt FROM text_records").use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(recordId.value, it.getString(0))
            assertEquals(draftId.value, it.getString(1))
            assertEquals(finalEdit.text, it.getString(2))
            assertEquals(firstRecordedAt, it.getLong(3))
            assertEquals(committedAt, it.getLong(4))
            assertTrue(it.isNull(5))
        }
    }

    @Test
    fun draftDeleteFailureRollsBackRecordAndCanBeRetried() = runBlocking {
        val saved = TextEdit("青瓦", 1)
        val draftId = requireNotNull(store.saveDraft(null, saved))
        val finalEdit = TextEdit("青瓦桥", 3)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER reject_draft_delete BEFORE DELETE ON text_drafts
               BEGIN SELECT RAISE(ABORT, 'test_reject_delete'); END""",
        )
        try {
            val failure = runCatching { store.completeDraft(draftId, finalEdit) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_delete") == true })
            assertEquals(saved, store.openDraft(draftId))
            sql.query("SELECT COUNT(*) FROM text_records").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            sql.execSQL("DROP TRIGGER reject_draft_delete")
        }

        val recordId = store.completeDraft(draftId, finalEdit)
        assertNull(store.openDraft(draftId))
        assertEquals(recordId.value, database.textDao().completedRecordId(draftId.value))
    }

    private suspend fun completeText(body: String): RecordId {
        val edit = TextEdit(body, body.length)
        val draftId = requireNotNull(store.saveDraft(null, edit))
        return store.completeDraft(draftId, edit)
    }

    @Test
    fun editingResumesItsOwnDraftAndDiscardPreservesPublishedText() = runBlocking {
        val record = completeText("旧桥")
        val editId = store.editRecord(record)
        assertEquals(TextEdit("旧桥", 2), store.openDraft(editId))
        val changed = TextEdit("新桥边", 1)
        store.saveDraft(editId, changed)
        reopenDatabase()

        assertEquals(editId, store.editRecord(record))
        assertEquals(changed, store.openDraft(editId))
        assertEquals(listOf(DraftPreview(editId, changed.text, record)), store.listDrafts())
        assertEquals("旧桥", store.openRecord(record))
        assertEquals(record, store.search("旧桥").single().id)
        assertTrue(store.search("新桥").isEmpty())

        val independent = requireNotNull(store.saveDraft(null, TextEdit("独立", 2)))
        store.discardDraft(editId)
        assertEquals(listOf(independent), store.listDrafts().map { it.id })
        assertNull(store.openDraft(editId))
        assertEquals("旧桥", store.openRecord(record))
        store.discardDraft(independent)
        assertTrue(store.listDrafts().isEmpty())
        assertTrue(runCatching { store.completeDraft(editId, changed) }.isFailure)
    }

    @Test
    fun searchAfterReopenFindsOnlyCurrentRecordAndOpensItsFullText() = runBlocking {
        val keyword = "青瓦桥"
        val body = "沿河走了一段路。".repeat(32) + keyword
        val published = completeText(body)
        val deleted = completeText("旧路线也经过青瓦桥")
        val hiddenEdit = store.editRecord(deleted)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE text_records SET deletedAt = ? WHERE id = ?",
            arrayOf<Any>(millis, deleted.value),
        )
        val draft = requireNotNull(store.saveDraft(null, TextEdit("青瓦桥的草稿", 3)))
        reopenDatabase()

        assertEquals(listOf(draft), store.listDrafts().map { it.id })
        val match = store.search(keyword).single()
        assertEquals(published, match.id)
        assertTrue(match.excerpt.isNotBlank() && match.excerpt.length < body.length)
        assertEquals(body, store.openRecord(match.id))
        assertNull(store.openRecord(deleted))
        assertNull(store.openDraft(hiddenEdit))
        assertTrue(runCatching { store.editRecord(deleted) }.isFailure)
        assertTrue(store.search("白石塔").isEmpty())
    }

    @Test
    fun searchTreatsSqlWildcardCharactersAsLiteralText() = runBlocking {
        val percent = completeText("进度 50%")
        val underscore = completeText("文件名 north_bridge")
        completeText("进度 50，文件名 northXbridge")

        assertEquals(listOf(percent), store.search("%").map { it.id })
        assertEquals(listOf(underscore), store.search("_").map { it.id })
    }
}
