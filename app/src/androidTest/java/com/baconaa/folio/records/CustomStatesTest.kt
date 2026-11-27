package com.baconaa.folio.records

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class CustomStatesTest : PhotoStoreFixture() {
    private val reading = CustomState("reading", "在读", "▤")
    private val done = CustomState("done", "读完", "✓")
    private val pending = CustomState("pending", "待整理", "◇")
    private val review = StateNote(LocalDate.of(2025, 3, 1), "读完第三章")

    @Test fun independentTargetsOnlyKeepConfirmedChangesThroughFailureRetryAndReopen() = runBlocking {
        listOf(reading, done, pending).forEach { store.defineState(it) }
        val draft = store.saveDraft(null, TextEdit("阅读记录", 0))!!
        val book = StateTarget(StateTargetKind.THING, store.createDraftObject(draft, ObjectKind.THING, ObjectInfo("书")).value)
        val person = StateTarget(StateTargetKind.PERSON, store.createDraftObject(draft, ObjectKind.PERSON, ObjectInfo("阿青")).value)
        val place = StateTarget(StateTargetKind.PLACE, store.createDraftPlace(draft, PlaceInfo("阅览室")).value)
        assertTrue(runCatching { store.setState(book, null, reading.id, "draft", null) }.isFailure)
        val record = store.completeMixedDraft(draft)
        val entry = StateTarget(record)
        store.setState(book, null, reading.id, "start", null)
        store.setState(entry, null, pending.id, "entry", null)
        store.setState(place, null, reading.id, "place", null)
        store.setState(person, null, pending.id, "person", null)
        assertEquals(setOf(person.id, entry.id), store.filterState(pending.id).map { it.id }.toSet())
        assertEquals(setOf(book.id, place.id), store.filterState(reading.id).map { it.id }.toSet())

        // Fail after the current value is written but before the optional history row commits.
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER fail_state_change BEFORE INSERT ON state_changes
            BEGIN SELECT RAISE(ABORT, 'injected state history write failure'); END""")
        assertTrue(runCatching { store.setState(book, reading.id, done.id, "finish", review) }.isFailure)
        assertEquals(reading, store.currentState(book))
        assertTrue(store.readStates(book).history.isEmpty())
        sql.execSQL("DROP TRIGGER fail_state_change")
        store.setState(book, reading.id, done.id, "finish", review)
        store.setState(book, done.id, reading.id, "correction", null)
        store.setState(book, reading.id, done.id, "finish", review)
        assertTrue(runCatching { store.setState(book, done.id, pending.id, "stale", null) }.isFailure)
        reopenDatabase()
        assertEquals(listOf(StateChange(reading, done, review)), store.readStates(book).history)
        assertEquals(reading, store.currentState(book))
        assertEquals(pending, store.currentState(entry))
        assertTrue(store.readStates(entry).history.isEmpty())
        assertFalse(store.filterState(done.id).any { it.id == book.id })

        store.moveToTrash(record)
        assertEquals(listOf(person.id), store.filterState(pending.id).map { it.id })
        assertTrue(runCatching { store.setState(entry, pending.id, reading.id, "trash", null) }.isFailure)
        store.restoreRecord(record)
        assertEquals(pending, store.readStates(entry).current)
        store.moveToTrash(record)
        store.deletePermanently(record)
        assertNull(database.customStateDao().current(entry.key))
        assertEquals(reading, store.currentState(book))
    }

    @Test fun protectedBackupRestoresStatePatternsCurrentSelectionsAndDatedHistory() = runBlocking {
        store.defineState(reading)
        store.defineState(reading)
        store.defineState(done)
        val entry = StateTarget(store.completeMixedDraft(store.saveDraft(null, TextEdit("日记", 0))!!))
        store.setState(entry, null, reading.id, "start", null)
        store.setState(entry, reading.id, done.id, "finish", review)
        val password = "test-passphrase".toCharArray()
        val data = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        store.setState(entry, done.id, reading.id, "correct", null)
        val preview = store.backups.preview(password) { data.inputStream() }
        try { store.backups.restore(preview) { data.inputStream() } }
        finally { store.backups.release(preview.directory) }
        reopenDatabase()
        assertEquals(done, store.currentState(entry))
        assertEquals(listOf(StateChange(reading, done, review)), store.readStates(entry).history)
        assertEquals(listOf(entry.id), store.filterState(done.id).map { it.id })
    }
}
