package com.baconaa.folio.records

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class PlaceTasksTest : PhotoStoreFixture() {
    @Test fun checkedItemsAndPendingExperiencesSurviveBackupIndependently() = runBlocking {
        suspend fun visit(street: String): Pair<PlaceId, RecordId> {
            val draft = store.saveDraft(null, TextEdit("菜单", 0))!!
            val place = store.createDraftPlace(draft, PlaceInfo("面馆", address = street))
            return place to store.completeMixedDraft(draft)
        }
        val (place, original) = visit("东街")
        val (other, wrong) = visit("西街")
        val fish = store.placeTasks.add(place, "豆花鱼")
        val window = store.placeTasks.add(place, "靠窗座位")
        store.placeTasks.check(window, true)
        repeat(2) { store.placeTasks.link(fish, original) }
        assertTrue(runCatching { store.placeTasks.link(fish, wrong) }.isFailure)
        val draft = store.newTaskDraft(fish)
        store.saveDraft(draft, TextEdit("周末去吃了", 0))
        reopenDatabase()
        assertEquals(listOf(original.value), store.placeTasks.read(place).first().experiences.map { it.id })
        val password = "本地备份测试123".toCharArray()
        val bytes = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        store.discardDraft(draft)
        val preview = store.backups.preview(password) { bytes.inputStream() }
        try { store.backups.restore(preview) { bytes.inputStream() } } finally { store.backups.release(preview.directory) }
        reopenDatabase()
        val second = store.completeMixedDraft(draft)
        assertEquals(second, store.completeMixedDraft(draft))
        val tasks = store.placeTasks.read(place)
        assertEquals(setOf(second.value, original.value), tasks.first().experiences.map { it.id }.toSet())
        assertFalse(tasks.first().row.checked)
        assertTrue(tasks.last().row.checked && tasks.last().experiences.isEmpty())
        assertTrue(store.placeTasks.read(other).isEmpty())
        store.moveToTrash(original)
        assertEquals(second.value, store.placeTasks.read(place).first().experiences.single().id)
        store.restoreRecord(original)
        assertEquals(2, store.placeTasks.read(place).first().experiences.size)
    }
}
