package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baconaa.folio.media.PhotoId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class PhotoDraftTest : PhotoStoreFixture() {
    @Test
    fun draftPhotosSurviveReopen() = runBlocking {
        val red = importPhoto(Color.RED)
        val blue = importPhoto(Color.BLUE)
        val id = store.appendPhotos(null, listOf(red.id))
        assertEquals(id, store.appendPhotos(id, listOf(blue.id, red.id)))

        val missing = PhotoId(UUID.randomUUID())
        assertTrue(runCatching { store.appendPhotos(id, listOf(blue.id, missing)) }.isFailure)
        reopenDatabase()

        assertPhotos(store.openDraftPhotos(id), red, blue, red)
        val preview = store.listDrafts().single()
        assertEquals(id, preview.id)
        assertEquals(red.id, preview.firstPhotoId)
    }
}
