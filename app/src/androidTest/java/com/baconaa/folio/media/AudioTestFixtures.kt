package com.baconaa.folio.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 合成 AAC 样本，仅用于文件/数据库/播放设备测试，不作为麦克风采集验收证据。 */
internal fun createAudioTestClip(context: Context, durationMillis: Long = 2_000): AudioClip {
    val originals = LocalAudioOriginals.get(context)
    val staging = originals.createRecordingFile()
    val sampleRate = 16_000
    val totalSamples = durationMillis * sampleRate / 1_000
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    var muxer: MediaMuxer? = null
    var codecStarted = false
    var muxerStarted = false
    try {
        val output = MediaMuxer(staging.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = output
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        codecStarted = true
        var written = 0L
        var inputEnded = false
        var outputEnded = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "AAC 测试样本生成超时" }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                    input.clear()
                    val count = min(totalSamples - written, input.remaining().toLong() / 2).toInt()
                    repeat(count) { offset ->
                        input.putShort((sin(2 * PI * 440 * (written + offset) / sampleRate) * 2_000).toInt().toShort())
                    }
                    val timestamp = written * 1_000_000 / sampleRate
                    written += count
                    inputEnded = written >= totalSamples
                    codec.queueInputBuffer(
                        index, 0, count * 2, timestamp,
                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                    )
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = output.addTrack(codec.outputFormat)
                    output.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (!muxerStarted) throw IOException("AAC 编码器尚未提供格式")
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        output.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        output.stop()
        muxerStarted = false
        output.release()
        muxer = null
        return originals.publishRecording(staging)
    } finally {
        try {
            if (codecStarted) codec.stop()
        } finally {
            codec.release()
            try {
                if (muxerStarted) muxer?.stop()
            } finally {
                muxer?.release()
                staging.delete()
            }
        }
    }
}
