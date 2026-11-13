package com.baconaa.folio.records

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalPhotoOriginals
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
class EditedTextCompletionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "edited-text-${UUID.randomUUID()}.db"
    private lateinit var database: FolioDatabase
    private lateinit var store: LocalTextStore
    private var now = 1_000L
    private val original = TextEdit("旧桥", 2)

    @Before
    fun openDatabase() {
        database = Room.databaseBuilder(context, FolioDatabase::class.java, databaseName).build()
        store = LocalTextStore(database, LocalPhotoOriginals.get(context), now = { now })
    }

    @After
    fun cleanUp() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private suspend fun publishOriginal(): RecordId {
        val draftId = requireNotNull(store.saveDraft(null, original))
        return store.completeDraft(draftId, original)
    }

    private suspend fun assertTimes(id: RecordId, firstRecordedAt: Long, modifiedAt: Long) {
        val record = requireNotNull(database.textDao().activeRecord(id.value))
        assertEquals(firstRecordedAt, record.firstRecordedAt)
        assertEquals(modifiedAt, record.modifiedAt)
    }

    @Test
    fun completionUpdatesOriginalAndOlderRetryKeepsLatestText() = runBlocking {
        val recordId = publishOriginal()
        val firstDraft = store.editRecord(recordId)
        store.saveDraft(firstDraft, TextEdit("新", 1))

        now = 2_000L
        val firstFinal = TextEdit("新桥", 2)
        assertEquals(recordId, store.completeDraft(firstDraft, firstFinal))
        assertNull(store.openDraft(firstDraft))
        assertTrue(store.search("旧桥").isEmpty())
        assertEquals(recordId, store.search("新桥").single().id)
        assertTimes(recordId, firstRecordedAt = 1_000L, modifiedAt = 2_000L)

        now = 3_000L
        val secondDraft = store.editRecord(recordId)
        val secondFinal = TextEdit("新桥竣工", 4)
        assertEquals(recordId, store.completeDraft(secondDraft, secondFinal))
        database.close()
        openDatabase()

        now = 4_000L
        assertEquals(recordId, store.completeDraft(firstDraft, firstFinal))
        assertEquals(secondFinal.text, store.openRecord(recordId))
        assertTimes(recordId, firstRecordedAt = 1_000L, modifiedAt = 3_000L)
    }

    @Test
    fun finishingWithOriginalTextClearsDraftWithoutChangingTimes() = runBlocking {
        val recordId = publishOriginal()
        val draft = store.editRecord(recordId)
        now = 2_000L
        store.saveDraft(draft, TextEdit("待修改", 3))
        assertEquals(recordId, store.completeDraft(draft, TextEdit(original.text, 0)))
        assertNull(store.openDraft(draft))
        assertEquals(original.text, store.openRecord(recordId))
        assertTimes(recordId, firstRecordedAt = 1_000L, modifiedAt = 1_000L)
    }

    @Test
    fun cleanupFailureRollsBackEditAndRetryCommitsIt() = runBlocking {
        val recordId = publishOriginal()
        val draft = store.editRecord(recordId)
        val saved = TextEdit("草稿", 1)
        store.saveDraft(draft, saved)
        val finalEdit = TextEdit("新桥", 2)
        now = 2_000L
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER reject_edit_cleanup BEFORE DELETE ON text_drafts
               BEGIN SELECT RAISE(ABORT, 'test_reject_edit_cleanup'); END""",
        )
        try {
            val failure = runCatching { store.completeDraft(draft, finalEdit) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("test_reject_edit_cleanup") == true })
            assertEquals(original.text, store.openRecord(recordId))
            assertEquals(saved, store.openDraft(draft))
            assertTimes(recordId, firstRecordedAt = 1_000L, modifiedAt = 1_000L)
        } finally {
            sql.execSQL("DROP TRIGGER reject_edit_cleanup")
        }
        assertEquals(recordId, store.completeDraft(draft, finalEdit))
        assertEquals(finalEdit.text, store.openRecord(recordId))
        assertNull(store.openDraft(draft))
    }

    @Test
    fun staleDraftCannotOverwriteNewerRecord() = runBlocking {
        val recordId = publishOriginal()
        val draft = store.editRecord(recordId)
        val saved = TextEdit("旧桥改造", 4)
        store.saveDraft(draft, saved)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE text_records SET body = ?, revision = revision + 1 WHERE id = ?",
            arrayOf("另一处已更新", recordId.value),
        )
        assertTrue(runCatching { store.completeDraft(draft, saved) }.isFailure)
        assertEquals("另一处已更新", store.openRecord(recordId))
        assertEquals(saved, store.openDraft(draft))
    }
}
