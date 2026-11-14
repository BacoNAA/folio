package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MixedContentFlowTest : PhotoStoreFixture() {
    @Test
    fun insertingBetweenPhotosPreservesOrderAndFocusUntilPublication() = runBlocking {
        val title = TextEdit("河边", 2)
        val record = store.completeDraft(requireNotNull(store.saveDraft(null, title)), title)
        val draft = store.editRecord(record)
        val titleItem = requireNotNull(store.openMixedDraft(draft)).items.single()
        val red = importPhoto(Color.RED)
        val blue = importPhoto(Color.BLUE)
        val firstPhoto = store.insertPhoto(draft, null, red.id, "红桥")
        val lastPhoto = store.insertPhoto(draft, null, blue.id, "蓝桥")
        val middle = store.insertText(draft, firstPhoto, TextEdit("桥", 1))
        store.editTextItem(draft, middle, TextEdit("桥🌉边", 3))
        val expected = listOf(
            titleItem,
            ContentItem.Photo(firstPhoto, red.id, "红桥"),
            ContentItem.Text(middle, "桥🌉边"),
            ContentItem.Photo(lastPhoto, blue.id, "蓝桥"),
        )

        reopenDatabase()
        val resumed = requireNotNull(store.openMixedDraft(draft))
        assertEquals(expected, resumed.items)
        assertEquals(middle, resumed.activeItemId)
        assertEquals(3, resumed.cursor)
        assertEquals(listOf(titleItem), store.openRecordContent(record)?.items)
        assertTrue(store.search("桥🌉边").isEmpty())

        val finalText = TextEdit("桥🌉边听雨", 6)
        val published = expected.toMutableList().apply { set(2, ContentItem.Text(middle, finalText.text)) }
        assertEquals(record, store.completeMixedDraft(draft, middle, finalText))
        reopenDatabase()
        assertEquals(record, store.completeMixedDraft(draft))
        val content = requireNotNull(store.openRecordContent(record))
        assertEquals(published, content.items)
        assertPhotos(content.photos, red, blue)
        assertEquals(record, store.search("听雨").single().id)
        assertEquals(record, store.search("河边").single().id)
    }
}
