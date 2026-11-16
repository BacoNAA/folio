package com.baconaa.folio.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import androidx.annotation.MainThread
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRecordingState(val isRecording: Boolean = false, val error: String? = null)

/**
 * 前台短录音控制器，进程内只允许一个录音。
 * get 和所有控制调用必须在主线程；prepare/stop 为 Android 同步操作。
 * 页面离开前应 stop 并把结果写入草稿，或 cancel；release 取消尚未保存的片段。
 * stop 成功仅代表原件已保存，草稿引用仍须由业务接口持久化。
 */
class LocalAudioRecorder private constructor(private val context: Context) {
    private val originals = LocalAudioOriginals.get(context)
    private var recorder: MediaRecorder? = null
    private var staging: File? = null
    private var released = false
    private val mutableState = MutableStateFlow(AudioRecordingState())
    val state: StateFlow<AudioRecordingState> = mutableState.asStateFlow()

    @MainThread
    fun start() {
        checkUsable()
        check(recorder == null) { "正在录音，请先停止或取消当前片段" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val message = "尚未获得麦克风权限，请允许录音后重试"
            mutableState.value = AudioRecordingState(error = message)
            throw SecurityException(message)
        }
        try {
            staging = originals.createRecordingFile()
            val active = newRecorder()
            recorder = active
            active.setOnErrorListener { source, _, _ ->
                if (recorder === source) {
                    discardCurrent()
                    mutableState.value = AudioRecordingState(error = "录音已中断，请重新录制；此前保存的片段仍保留")
                }
            }
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioChannels(1)
            active.setAudioSamplingRate(44_100)
            active.setAudioEncodingBitRate(96_000)
            active.setOutputFile(requireNotNull(staging).absolutePath)
            active.prepare()
            active.start()
            mutableState.value = AudioRecordingState(isRecording = true)
        } catch (failure: Exception) {
            discardCurrent()
            val message = if (failure is SecurityException) "无法使用麦克风，请检查录音权限后重试"
                else "无法开始录音，请检查麦克风是否被占用及可用空间后重试"
            mutableState.value = AudioRecordingState(error = message)
            if (failure is SecurityException) throw SecurityException(message, failure)
            throw IOException(message, failure)
        }
    }

    /** 同步停止并验证/发布文件；失败只清理本次临时文件，绝不覆盖此前的片段。 */
    @MainThread
    fun stop(): AudioClip {
        checkUsable()
        val active = checkNotNull(recorder) { "当前没有正在录制的片段" }
        val file = checkNotNull(staging)
        try {
            // Android throws RuntimeException if stop is called before any valid audio arrived.
            active.stop()
            active.release()
            recorder = null
            val clip = originals.publishRecording(file)
            mutableState.value = AudioRecordingState()
            return clip
        } catch (failure: Exception) {
            val message = "录音过短、中断或保存失败，请重新录制；此前保存的片段仍保留"
            mutableState.value = AudioRecordingState(error = message)
            throw IOException(message, failure)
        } finally {
            discardCurrent()
        }
    }

    @MainThread
    fun cancel() {
        checkUsable()
        discardCurrent()
        mutableState.value = AudioRecordingState()
    }

    @MainThread
    fun release() {
        checkMainThread()
        if (released) return
        discardCurrent()
        mutableState.value = AudioRecordingState()
        released = true
        if (instance === this) instance = null
    }

    private fun discardCurrent() {
        val active = recorder
        recorder = null
        try {
            active?.release()
        } finally {
            staging?.delete()
            staging = null
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else MediaRecorder()

    private fun checkUsable() {
        checkMainThread()
        check(!released) { "录音控制器已释放，请重新获取" }
    }

    companion object {
        private var instance: LocalAudioRecorder? = null

        @MainThread
        fun get(context: Context): LocalAudioRecorder {
            checkMainThread()
            return instance ?: LocalAudioRecorder(context.applicationContext).also { instance = it }
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "录音控制必须在主线程调用" }
        }
    }
}
