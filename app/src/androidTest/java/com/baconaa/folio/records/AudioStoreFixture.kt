package com.baconaa.folio.records

import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.AudioClip
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.createAudioTestClip
import org.junit.After

internal abstract class AudioStoreFixture : PhotoStoreFixture() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val audioOriginals = LocalAudioOriginals.get(context)
    private val clips = mutableListOf<AudioClip>()

    protected fun audioClip(): AudioClip = createAudioTestClip(context).also { clips.add(it) }

    protected fun audioFile(id: AudioId) = audioOriginals.originalFile(id)

    protected suspend fun publishAudio(id: AudioId): RecordId =
        store.completeMixedDraft(store.appendAudio(null, id))

    @After
    fun removeTestAudio() {
        clips.forEach { audioOriginals.deleteOriginal(it.id) }
    }
}
