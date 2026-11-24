package com.baconaa.folio.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.baconaa.folio.records.MediaImportException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@JvmInline
value class VideoId(val value: UUID)

/** 识别复制到本机的实际封装和音视频轨。封面提取独立于解码验证。 */
internal suspend fun inspectVideo(file: File): Long? {
    val extractor = MediaExtractor()
    try {
        val duration = MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(file.path)
            if (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) != "video/mp4")
                throw MediaImportException("视频格式不支持，请选择 H.264 编码的 MP4")
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        }
        extractor.setDataSource(file.path)
        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        val video = tracks.indexOfFirst { it.startsWith("video/") }
        if (video < 0 || tracks[video] != MediaFormat.MIMETYPE_VIDEO_AVC)
            throw MediaImportException("视频格式不支持，请选择 H.264 编码的 MP4")
        val audio = tracks.indexOfFirst { it.startsWith("audio/") }
        if (audio >= 0 && tracks[audio] != MediaFormat.MIMETYPE_AUDIO_AAC)
            throw MediaImportException("视频音轨格式不支持，请选择使用 AAC 音轨的 MP4")
        decodeFirstSample(extractor, video)
        if (audio >= 0) decodeFirstSample(extractor, audio)
        return duration
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: MediaImportException) {
        throw failure
    } catch (failure: IOException) {
        throw MediaImportException("视频格式无法读取或解码，请重新选择 MP4", failure)
    } catch (failure: RuntimeException) {
        throw MediaImportException("视频格式无法读取或解码，请重新选择 MP4", failure)
    } finally {
        extractor.release()
    }
}

private suspend fun decodeFirstSample(extractor: MediaExtractor, track: Int) {
    val format = extractor.getTrackFormat(track)
    val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        ?: throw MediaImportException("当前设备无法解码此视频格式，请重新选择")
    val codec = MediaCodec.createByCodecName(decoder)
    try {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var ended = false
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val input = if (ended) -1 else codec.dequeueInputBuffer(10_000)
            if (input >= 0) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0 && extractor.sampleTrackIndex >= 0)
                    throw MediaImportException("视频受保护，请选择可直接播放的文件")
                val size = extractor.readSampleData(checkNotNull(codec.getInputBuffer(input)), 0)
                ended = size < 0
                codec.queueInputBuffer(input, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
                    if (ended) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                if (!ended) extractor.advance()
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                codec.releaseOutputBuffer(output, false)
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) return
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        throw MediaImportException("视频数据不完整或无法解码，请重新选择")
    } finally {
        codec.release()
        extractor.unselectTrack(track)
    }
}
