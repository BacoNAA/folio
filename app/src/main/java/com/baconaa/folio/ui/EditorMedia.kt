package com.baconaa.folio.ui

import android.content.Context
import android.net.Uri
import com.baconaa.folio.media.*
import com.baconaa.folio.records.ItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设备控制在主线程，原件导入由已有媒体后端切换到 I/O 线程。 */
interface EditorMedia {
    val recording: StateFlow<AudioRecordingState>
    val playback: StateFlow<AudioPlaybackState>
    suspend fun importPhoto(uri: Uri): PhotoId
    fun start()
    fun stop(): AudioClip
    fun cancel()
    fun play(id: AudioId)
    fun pause(id: AudioId)
    fun stopPlayback()
    fun release()
}

class LocalEditorMedia(context: Context) : EditorMedia {
    private val context = context.applicationContext
    private val photos = LocalPhotoOriginals.get(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: LocalAudioRecorder? = null
    private var player: LocalAudioPlayback? = null
    private val recordingState = MutableStateFlow(AudioRecordingState())
    private val playbackState = MutableStateFlow(AudioPlaybackState())
    override val recording = recordingState.asStateFlow()
    override val playback = playbackState.asStateFlow()

    // 页面真正使用音频时才取得控制权；仅打开页面不持有或释放其他调用方的控制器。
    private fun recorder(): LocalAudioRecorder = recorder ?: LocalAudioRecorder.get(context).also {
        recorder = it
        scope.launch { it.state.collect { state -> recordingState.value = state } }
    }

    private fun player(): LocalAudioPlayback = player ?: LocalAudioPlayback.get(context).also {
        player = it
        scope.launch { it.state.collect { state -> playbackState.value = state } }
    }

    override suspend fun importPhoto(uri: Uri) = photos.importPhoto(uri)
    override fun start() { stopPlayback(); recorder().start() }
    override fun stop() = checkNotNull(recorder).stop()
    override fun cancel() { recorder?.cancel() }
    override fun play(id: AudioId) = player().play(id)
    override fun pause(id: AudioId) { player?.pause(id) }
    override fun stopPlayback() { player?.stop() }
    override fun release() { recorder?.release(); player?.release(); scope.cancel() }
}

data class ExternalTarget(val draftId: String?, val recordId: String?, val afterId: String?,
    val returnPage: Page, val detailOrigin: Page)

sealed interface PendingAttachment {
    val after: ItemId?
    data class Photo(val id: PhotoId, override val after: ItemId?) : PendingAttachment
    data class Audio(val clip: AudioClip, override val after: ItemId?) : PendingAttachment
}
