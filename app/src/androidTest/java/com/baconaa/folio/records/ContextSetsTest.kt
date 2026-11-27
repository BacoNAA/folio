package com.baconaa.folio.records

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class ContextSetsTest : PhotoStoreFixture() {
    @Test fun manualAndAppliedConditionsMatchAndEditsDoNotRewritePublishedRecords() = runBlocking {
        suspend fun draft(name: String) = store.saveDraft(null, TextEdit(name, 0))!!
        val seed = draft("标签资料")
        val rain = store.createDraftTag(seed, "雨天")
        val solo = store.createDraftTag(seed, "独处")
        val coffee = store.createDraftTag(seed, "咖啡")
        val art = com.baconaa.folio.media.StampArtwork.icon("☕")
        store.saveStamp(seed, coffee, "咖啡", art)
        val place = store.createDraftPlace(seed, PlaceInfo("街角店"))
        val experience = store.completeMixedDraft(seed)
        val task = store.placeTasks.add(place, "试手冲")
        store.placeTasks.link(task, experience)
        val state = CustomState("sorted", "已整理", "✓")
        store.defineState(state)
        store.setState(StateTarget(experience), null, state.id, "one", StateNote(java.time.LocalDate.of(2026, 11, 1), "回访"))
        val stamp = store.listTags().single { it.id == coffee }
        val scale = store.saveFeelingScale(null, "松弛", listOf("紧绷", "自在"))
        val feeling = scale.choice(2)
        val set = store.contextSets.save(null, "终于慢下来", listOf(rain.value, solo.value), listOf(feeling), stamp)
        val a = draft("甲")
        store.contextSets.apply(set, a)
        assertTrue(store.contextSets.matches(set).isEmpty())
        val first = store.completeMixedDraft(a)
        val b = draft("乙")
        store.saveDraftTags(b, listOf(rain, solo))
        store.setDraftFeeling(b, feeling)
        val second = store.completeMixedDraft(b)
        val c = draft("丙")
        store.saveDraftTags(c, listOf(rain))
        val third = store.completeMixedDraft(c)
        val password = "情境备份口令-123".toCharArray()
        val bytes = ByteArrayOutputStream().also { store.backups.writeProtected(it, password) }.toByteArray()
        store.contextSets.save(set, "新搭配", listOf(rain.value, coffee.value), emptyList(), null)
        val preview = store.backups.preview(password) { bytes.inputStream() }
        try { store.backups.restore(preview) { bytes.inputStream() } } finally { store.backups.release(preview.directory) }
        reopenDatabase()
        val restored = store.contextSets.list().single()
        assertEquals(coffee.value, restored.row.stampTag)
        assertArrayEquals(art, store.readStamp(store.listTags().single { it.id == coffee }))
        assertEquals("回访", store.readStates(StateTarget(experience)).history.single().entry.note)
        assertEquals(experience.value, store.placeTasks.read(place).single().experiences.single().id)
        assertEquals(setOf(first.value, second.value), store.contextSets.matches(set).map { it.id }.toSet())
        store.saveFeelingScale(scale, scale.name, listOf("紧绷", "普通", "自在"))
        val pending = draft("尚无标签")
        assertTrue(runCatching { store.contextSets.apply(set, pending) }.isFailure)
        assertTrue(store.openDraftTags(pending)!!.isEmpty())
        assertTrue(runCatching { store.contextSets.matches(set) }.isFailure)
        store.contextSets.save(set, "雨天咖啡", listOf(rain.value, coffee.value), emptyList(), null)
        assertEquals(listOf(rain, solo), store.openRecordContent(first)!!.tags.map { it.id })
        val edit = store.editRecord(third)
        store.saveDraftTags(edit, listOf(rain, coffee))
        assertFalse(store.contextSets.matches(set).any { it.id == third.value })
        store.completeMixedDraft(edit)
        assertTrue(store.contextSets.matches(set).any { it.id == third.value })
        store.moveToTrash(third)
        assertFalse(store.contextSets.matches(set).any { it.id == third.value })
    }
}
