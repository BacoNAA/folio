package com.baconaa.folio.records

import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.AudioClip
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.createAudioTestClip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

internal abstract class AudioStoreFixture : PhotoStoreFixture() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val audioOriginals = LocalAudioOriginals.get(context)
    private val clips = mutableListOf<AudioClip>()

    protected fun audioClip(): AudioClip = createAudioTestClip(context).also { clips.add(it) }

    protected fun audioFile(id: AudioId) = audioOriginals.originalFile(id)

    protected suspend fun publishAudio(id: AudioId): RecordId =
        store.completeMixedDraft(store.appendAudio(null, id))

    protected suspend fun playsToEnd(id: AudioId) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var playback: LocalAudioPlayback
        instrumentation.runOnMainSync {
            playback = LocalAudioPlayback.get(context)
            playback.play(id)
        }
        try {
            val state = withTimeout(12_000) { playback.state.first { it.hasEnded || it.error != null } }
            assertNull(state.error)
            assertEquals(id, state.audioId)
            assertTrue(state.hasEnded)
        } finally {
            instrumentation.runOnMainSync { playback.release() }
        }
    }

    @After
    fun removeTestAudio() {
        clips.forEach { audioOriginals.deleteOriginal(it.id) }
    }
}
