package com.baconaa.folio.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioPlaybackState(
    val audioId: AudioId? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasEnded: Boolean = false,
    val error: String? = null,
)

/**
 * 进程内共享一个 ExoPlayer。所有 get/控制调用均在主线程，状态可从任意线程观察。
 * 列表项的暂停/停止须传自身 ID，过期页面不能停止后来播放的另一段。
 * 应用前台生命周期的统一持有者负责 stop/release，单个列表项不释放共享播放器。
 */
@OptIn(UnstableApi::class)
class LocalAudioPlayback private constructor(context: Context) {
    private val originals = LocalAudioOriginals.get(context)
    private val mutableState = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = mutableState.asStateFlow()
    private var released = false
    private var currentId: AudioId? = null
    private var error: String? = null
    private val player = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also {
            it.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateState()

                override fun onPlayerError(failure: PlaybackException) {
                    error = "录音无法播放，请重试或检查原文件是否仍可读取"
                    updateState()
                }
            })
        }

    /** 播放另一段会先停止上一段；再次播放当前暂停片段从原位置继续。 */
    @MainThread
    fun play(id: AudioId) {
        checkUsable()
        if (currentId == id && error == null && player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            player.play()
            updateState()
            return
        }
        player.stop()
        player.clearMediaItems()
        currentId = id
        error = null
        val file = originals.originalFile(id)
        if (!file.isFile || !file.canRead()) {
            error = "录音原文件不存在或无法读取，无法播放"
            updateState()
            return
        }
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId(id.value.toString()).setUri(Uri.fromFile(file)).build())
            player.prepare()
            player.play()
        } catch (failure: RuntimeException) {
            player.stop()
            error = "录音无法播放，请重试"
        }
        updateState()
    }

    @MainThread
    fun pause(id: AudioId) {
        checkUsable()
        if (currentId != id) return
        player.pause()
        updateState()
    }

    /** id 非空时只停止该片段；无参调用由统一生命周期持有者使用。 */
    @MainThread
    fun stop(id: AudioId? = null) {
        checkUsable()
        if (id != null && currentId != id) return
        player.stop()
        player.clearMediaItems()
        currentId = null
        error = null
        updateState()
    }

    @MainThread
    fun release() {
        checkMainThread()
        if (released) return
        released = true
        player.release()
        currentId = null
        mutableState.value = AudioPlaybackState()
        if (instance === this) instance = null
    }

    private fun updateState() {
        if (released) return
        mutableState.value = AudioPlaybackState(
            audioId = currentId,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            hasEnded = player.playbackState == Player.STATE_ENDED,
            error = error,
        )
    }

    private fun checkUsable() {
        checkMainThread()
        check(!released) { "播放器已释放，请重新获取" }
    }

    companion object {
        private var instance: LocalAudioPlayback? = null

        @MainThread
        fun get(context: Context): LocalAudioPlayback {
            checkMainThread()
            return instance ?: LocalAudioPlayback(context.applicationContext).also { instance = it }
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "播放控制必须在主线程调用" }
        }
    }
}
