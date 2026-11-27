package com.baconaa.folio.records

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordChapterTest : PhotoStoreFixture() {
    @Test fun overlappingChaptersKeepTheirOwnOrderAndCoverIdentity() = runBlocking {
        val photo = importPhoto(Color.GREEN)
        val draft = store.saveDraft(null, TextEdit("搬家", 0))!!
        store.insertPhoto(draft, null, photo.id, "阳台")
        val moving = store.completeMixedDraft(draft)
        val college = store.completeMixedDraft(store.saveDraft(null, TextEdit("第一次做饭", 0), EventTime.Description("大学时"))!!)
        val first = RecordGroupId("home")
        val second = RecordGroupId("intern")
        val cover = store.chapterPhotos().single()
        val profile = ChapterProfile("第一次独居", "2023", "2024-03", "老房子", cover.cover)
        store.saveChapter(first, null, profile)
        store.saveChapter(second, null, ChapterProfile("杭州实习", "2023-09"))
        store.appendToGroup(first, listOf(moving, college))
        store.appendToGroup(second, listOf(moving))
        assertTrue(runCatching { store.saveChapter(first, profile, profile.copy(end = "2022")) }.isFailure)
        store.saveChapter(first, profile, profile.copy(end = "2023-11"))
        store.reorderGroup(first, listOf(moving, college), listOf(college, moving))
        store.removeFromGroup(first, moving)
        reopenDatabase()
        assertTrue(store.listGroups().isEmpty())
        assertEquals(setOf(first, second), store.listChapters().map { it.id }.toSet())
        val chapter = store.openGroup(first)!!
        assertEquals("大学时", chapter.members.single().eventTime?.label)
        assertEquals(profile.copy(end = "2023-11"), chapter.chapter)
        assertEquals(cover, chapter.cover)
        assertEquals(moving, store.openGroup(second)!!.members.single().id)
        assertEquals(moving, store.search("搬家").single().id)
        store.moveToTrash(moving)
        assertNull(store.openGroup(first)!!.cover)
        store.restoreRecord(moving)
        assertEquals(cover, store.openGroup(first)!!.cover)
    }
}
