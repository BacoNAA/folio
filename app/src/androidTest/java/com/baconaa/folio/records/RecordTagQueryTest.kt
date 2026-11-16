package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordTagQueryTest : AudioStoreFixture() {
    @Test
    fun searchUsesFormalTextChecklistMediaCaptionsAndCurrentTagNames() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val audio = audioClip()
        val draft = requireNotNull(store.saveDraft(null, TextEdit("青瓦桥", 3)))
        store.insertPhoto(draft, null, photo.id, "桥下珊瑚")
        store.insertAudio(draft, null, audio.id, "Deep Waves 100%_海浪")
        store.appendChecklist(draft, listOf(ChecklistEntry(ChecklistEntryId("umbrella"), "带雨伞")))
        val tag = store.createDraftTag(draft, "值得再去")
        assertTrue(store.search("珊瑚").isEmpty())
        assertTrue(store.queryRecords("值得再去").isEmpty())
        val record = store.completeMixedDraft(draft)
        listOf("青瓦", "珊瑚", "waves", "100%_", "雨伞", "值得再去").forEach { keyword ->
            assertEquals(listOf(record), store.search(keyword).map { it.id })
            assertEquals(listOf(record), store.queryRecords(keyword).map { it.id })
        }
        assertTrue(store.queryRecords("100XY").isEmpty())
        val edit = store.editRecord(record)
        val audioItem = store.openMixedDraft(edit)!!.items.filterIsInstance<ContentItem.Audio>().single()
        store.editAudioCaption(edit, audioItem.id, "尚未完成的声音说明")
        val privateTag = store.createDraftTag(edit, "仅在草稿出现")
        assertTrue(store.queryRecords("尚未完成").isEmpty())
        assertTrue(store.search("仅在草稿").isEmpty())
        assertTrue(store.queryRecords(tagIds = setOf(privateTag)).isEmpty())
        assertEquals(listOf(record), store.queryRecords("waves").map { it.id })
        store.renameTag(tag, "再次探访")
        assertTrue(store.queryRecords("值得再去").isEmpty())
        assertEquals(listOf(record), store.queryRecords("再次探访").map { it.id })
        store.moveToTrash(record)
        assertTrue(store.search("青瓦").isEmpty())
        assertTrue(store.queryRecords().isEmpty())
        store.restoreRecord(record)
        assertEquals(listOf(record), store.queryRecords("再次探访", setOf(tag)).map { it.id })
        assertEquals(listOf(Tag(tag, "再次探访")), store.queryRecords().single().tags)
    }

    @Test
    fun tagIdsMatchAnyWhileKeywordAndStarredMustAlsoMatchAndPinnedOrderIsPreserved() = runBlocking {
        val firstDraft = textDraft("山间小路")
        val firstTag = store.createDraftTag(firstDraft, "出游")
        val first = store.completeMixedDraft(firstDraft)
        val secondDraft = textDraft("河边小路")
        val secondTag = store.createDraftTag(secondDraft, "出游")
        val second = store.completeMixedDraft(secondDraft)
        val thirdDraft = textDraft("街角小路")
        store.saveDraftTags(thirdDraft, listOf(firstTag, secondTag))
        val third = store.completeMixedDraft(thirdDraft)
        store.setStarred(first, true)
        store.setStarred(third, true)
        store.setPinned(first, true)
        assertEquals(listOf(first, third, second), store.queryRecords("小路", setOf(firstTag, secondTag)).map { it.id })
        assertEquals(listOf(first, third), store.queryRecords("小路", setOf(firstTag), true).map { it.id })
        assertEquals(listOf(third), store.queryRecords("小路", setOf(secondTag), true).map { it.id })
        assertEquals(listOf(first), store.queryRecords("山间", setOf(firstTag, secondTag), true).map { it.id })
        assertTrue(store.queryRecords("山间", setOf(secondTag), true).isEmpty())
        assertTrue(store.queryRecords(tagIds = setOf(TagId("missing"))).isEmpty())
        assertEquals(listOf(first, third, second), store.queryRecords("出游").map { it.id })
        assertEquals(listOf(first, third), store.queryRecords(starredOnly = true).map { it.id })
        assertEquals(store.listRecentRecords(), store.queryRecords())
        store.renameTag(firstTag, "徒步")
        assertEquals(listOf(third, second), store.queryRecords("出游").map { it.id })
        assertEquals(listOf(first, third), store.queryRecords(tagIds = setOf(firstTag)).map { it.id })
    }

    private suspend fun textDraft(text: String) = requireNotNull(store.saveDraft(null, TextEdit(text, text.length)))
}
