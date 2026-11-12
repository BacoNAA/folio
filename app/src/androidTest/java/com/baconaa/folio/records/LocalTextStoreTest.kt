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
}
