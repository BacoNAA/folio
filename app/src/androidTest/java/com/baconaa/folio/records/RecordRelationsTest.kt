package com.baconaa.folio.records

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordRelationsTest : PhotoStoreFixture() {
    @Test fun replacingFromEitherEndAndRestoringOrErasingATargetPreserveTheRightEdges() = runBlocking {
        suspend fun record(text: String) = store.completeMixedDraft(store.saveDraft(null, TextEdit(text, 0))!!)
        val repair = record("维修单")
        val old = record("旧凭证")
        val fresh = record("新凭证")
        val edit = store.editRecord(repair)
        store.changeRelation(repair, null, old)
        store.changeRelation(old, null, repair)
        assertEquals(setOf(repair), store.relationChoices(old).related)
        assertFalse(store.relationChoices(old).records.any { it.id == old })
        store.moveToTrash(fresh)
        assertTrue(runCatching { store.changeRelation(repair, old, fresh) }.isFailure)
        assertEquals(setOf(old), store.relationChoices(repair).related)
        store.restoreRecord(fresh)
        store.changeRelation(repair, old, fresh)
        store.changeRelation(repair, old, fresh)
        reopenDatabase()
        assertTrue(store.relationChoices(old).related.isEmpty())
        assertEquals(setOf(repair), store.relationChoices(fresh).related)
        store.moveToTrash(repair)
        assertTrue(store.relationChoices(fresh).related.isEmpty())
        store.restoreRecord(repair)
        assertEquals(setOf(fresh), store.relationChoices(repair).related)
        store.changeRelation(fresh, repair, null)
        store.completeMixedDraft(edit)
        assertTrue(store.relationChoices(repair).related.isEmpty())
        store.changeRelation(repair, null, fresh)
        store.moveToTrash(fresh)
        store.deletePermanently(fresh)
        assertTrue(store.relationChoices(repair).related.isEmpty())
        assertTrue(database.relationDao().related(repair.value).isEmpty())
    }
}
