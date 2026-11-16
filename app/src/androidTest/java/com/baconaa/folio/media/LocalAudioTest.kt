package com.baconaa.folio.media

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.MainActivity
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val originals = LocalAudioOriginals.get(context)
    private val saved = mutableListOf<AudioId>()

    @After
    fun cleanUp() {
        main {
            LocalAudioRecorder.get(context).release()
            LocalAudioPlayback.get(context).release()
        }
        saved.forEach(originals::deleteOriginal)
    }

    @Test
    fun platformRecordingCanBeStoppedAndReopenedById() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            main { LocalAudioRecorder.get(context).start() }
            SystemClock.sleep(1_500)
            val clip = main { LocalAudioRecorder.get(context).stop() }.also { saved += it.id }
            main { LocalAudioRecorder.get(context).release() }

            val reopenedId = AudioId(UUID.fromString(clip.id.value.toString()))
            val bytes = originals.openOriginal(reopenedId).use { it.readBytes() }
            assertTrue(bytes.isNotEmpty())
            assertTrue(clip.durationMillis > 0)
            assertEquals(clip, originals.inspect(reopenedId))
            assertArrayEquals(bytes, originals.openOriginal(reopenedId).use { it.readBytes() })
            assertFalse(main { LocalAudioRecorder.get(context).state.value.isRecording })
        }
    }

    @Test
    fun cancelDoesNotPublishAndInvalidRecordingPreservesExistingClip() {
        val good = createAudioTestClip(context).also { saved += it.id }
        val bytes = originals.openOriginal(good.id).use { it.readBytes() }
        val before = publishedFiles()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            main { LocalAudioRecorder.get(context).start() }
            SystemClock.sleep(200)
            main { LocalAudioRecorder.get(context).cancel() }
        }
        assertEquals(before, publishedFiles())
        val invalid = originals.createRecordingFile().apply { writeText("incomplete recording") }
        val failure = runCatching { originals.publishRecording(invalid) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(invalid.exists())
        assertEquals(before, publishedFiles())
        assertArrayEquals(bytes, originals.openOriginal(good.id).use { it.readBytes() })
    }

    @Test
    fun immediateStopNeverPublishesUnreadableAudioOrLosesExistingClip() {
        val good = createAudioTestClip(context).also { saved += it.id }
        val bytes = originals.openOriginal(good.id).use { it.readBytes() }
        val before = publishedFiles()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val result = main {
                val recorder = LocalAudioRecorder.get(context)
                recorder.start()
                runCatching { recorder.stop() }
            }
            // Devices differ in how soon their first valid AAC frame arrives.
            val clip = result.getOrNull()
            if (clip == null) {
                assertTrue(result.exceptionOrNull() is IOException)
                assertNotNull(main { LocalAudioRecorder.get(context).state.value.error })
                assertEquals(before, publishedFiles())
            } else {
                saved += clip.id
                assertEquals(clip, originals.inspect(clip.id))
            }
            assertFalse(main { LocalAudioRecorder.get(context).state.value.isRecording })
        }
        assertArrayEquals(bytes, originals.openOriginal(good.id).use { it.readBytes() })
        assertTrue(File(context.filesDir, "audio-originals/.pending").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun permissionDeniedBeforeRecordingLeavesExistingFilesUntouched() {
        val good = createAudioTestClip(context).also { saved += it.id }
        val before = publishedFiles()
        // Exercise the actual start permission guard without revoking permission and killing the test process.
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        main {
            LocalAudioRecorder.get(context).release()
            val recorder = LocalAudioRecorder.get(denied)
            val failure = runCatching { recorder.start() }.exceptionOrNull()
            assertTrue(failure is SecurityException)
            assertTrue(requireNotNull(failure?.message).contains("麦克风权限"))
            assertFalse(recorder.state.value.isRecording)
            assertNotNull(recorder.state.value.error)
            recorder.release()
        }
        assertEquals(before, publishedFiles())
        assertEquals(good, originals.inspect(good.id))
    }

    @Test
    fun singlePlayerSwitchesClipsAndIgnoresControlsForPreviousClip() {
        val first = createAudioTestClip(context, 4_000).also { saved += it.id }
        val second = createAudioTestClip(context, 4_000).also { saved += it.id }
        ActivityScenario.launch(MainActivity::class.java).use {
            val playback = main { LocalAudioPlayback.get(context) }
            assertSame(playback, main { LocalAudioPlayback.get(context) })
            main { playback.play(first.id) }
            awaitPlaying(playback, first.id)
            main { playback.play(second.id) }
            awaitPlaying(playback, second.id)
            main {
                playback.pause(first.id)
                playback.stop(first.id)
                assertEquals(second.id, playback.state.value.audioId)
                assertTrue(playback.state.value.isPlaying)
                playback.pause(second.id)
                assertFalse(playback.state.value.isPlaying)
                playback.play(second.id)
            }
            awaitPlaying(playback, second.id)
            main {
                playback.stop(second.id)
                assertEquals(AudioPlaybackState(), playback.state.value)
                playback.release()
                assertTrue(runCatching { playback.play(first.id) }.exceptionOrNull() is IllegalStateException)
            }
        }
    }

    @Test
    fun missingAndCorruptFilesExposePlaybackFailure() {
        val playback = main { LocalAudioPlayback.get(context) }
        main { playback.play(AudioId(UUID.randomUUID())) }
        assertNotNull(playback.state.value.error)
        val corruptId = AudioId(UUID.randomUUID()).also { saved += it }
        originals.originalFile(corruptId).apply {
            parentFile?.mkdirs()
            writeText("not an audio clip")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            main { playback.play(corruptId) }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (playback.state.value.error == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20)
            }
            assertEquals(corruptId, playback.state.value.audioId)
            assertNotNull("ExoPlayer decoder failure should be observable", playback.state.value.error)
            assertFalse(playback.state.value.isPlaying)
        }
        assertTrue(runCatching { LocalAudioPlayback.get(context) }.exceptionOrNull() is IllegalStateException)
    }

    private fun awaitPlaying(playback: LocalAudioPlayback, id: AudioId) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!playback.state.value.isPlaying && playback.state.value.error == null &&
            SystemClock.elapsedRealtime() < deadline
        ) SystemClock.sleep(20)
        assertEquals(id, playback.state.value.audioId)
        assertTrue("Playback did not start: ${playback.state.value}", playback.state.value.isPlaying)
    }

    private fun publishedFiles(): Set<String> = File(context.filesDir, "audio-originals")
        .listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
}
