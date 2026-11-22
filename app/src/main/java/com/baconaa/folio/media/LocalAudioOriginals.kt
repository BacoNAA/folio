package com.baconaa.folio.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

@JvmInline
value class AudioId(val value: UUID)

data class AudioClip(val id: AudioId, val durationMillis: Long) {
    init {
        require(durationMillis > 0) { "录音时长必须大于零" }
    }
}

class LocalAudioOriginals private constructor(private val context: Context) {
    private val originals get() = File(context.filesDir, "audio-originals")

    private val pending by lazy {
        val directory = Files.createDirectories(File(originals, ".pending").toPath()).toFile()
        // Only unfinished recordings from the previous process live here; published clips never do.
        directory.listFiles()?.forEach { it.delete() }
        directory
    }

    /** 在 I/O 线程读取；调用方关闭流。只有已原子发布的文件可以按 ID 读取。 */
    fun openOriginal(id: AudioId): InputStream = originalFile(id).inputStream()

    /** Room 写入引用前在 I/O 线程核验真实文件和时长，不信任调用方传入的元数据。 */
    internal fun inspect(id: AudioId): AudioClip = AudioClip(id, inspectDuration(originalFile(id)))

    /** 仅由引用清理确认无引用后调用；已不存在视为成功。 */
    internal fun deleteOriginal(id: AudioId) {
        Files.deleteIfExists(originalFile(id).toPath())
    }

    internal fun importedFile(id: AudioId) = File(originals, "${id.value}.audio")

    internal fun originalFile(id: AudioId): File =
        importedFile(id).takeIf { it.exists() } ?: File(originals, "${id.value}.m4a")

    internal fun createRecordingFile(): File = File.createTempFile("audio-", ".part", pending)

    /** 只接收本模块创建的临时录音；返回后业务层才能把片段加入草稿。 */
    internal fun publishRecording(staging: File): AudioClip {
        require(staging.parentFile == pending) { "录音必须来自本机临时目录" }
        try {
            val duration = inspectDuration(staging)
            RandomAccessFile(staging, "rw").use { it.fd.sync() }
            val clip = AudioClip(AudioId(UUID.randomUUID()), duration)
            Files.move(staging.toPath(), originalFile(clip.id).toPath(), ATOMIC_MOVE)
            return clip
        } finally {
            staging.delete()
        }
    }

    internal fun inspectDuration(file: File): Long {
        if (!file.isFile || !file.canRead() || file.length() == 0L) {
            throw IOException("录音文件不存在或无法读取")
        }
        val extractor = MediaExtractor()
        try {
            file.inputStream().use {
                try {
                    extractor.setDataSource(it.fd)
                } catch (failure: IOException) {
                    throw IOException("无法读取音频格式，请选择 MP3 或 M4A（AAC）录音", failure)
                }
            }
            val tracks = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            if (tracks.any { it.startsWith("video/") }) throw IOException("请选择音频文件，当前文件包含视频")
            val track = tracks.indexOfFirst { it == MediaFormat.MIMETYPE_AUDIO_AAC || it == MediaFormat.MIMETYPE_AUDIO_MPEG }
            if (track < 0) throw IOException("没有可读取的 MP3 或 AAC 音轨，请重新选择录音")
            val format = extractor.getTrackFormat(track)
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null) {
                throw IOException("当前设备无法解码这段录音，请重新选择")
            }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000
            } else 0L
            extractor.selectTrack(track)
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                throw IOException("这段录音受保护，请选择可直接播放的文件")
            }
            if (duration <= 0 || extractor.readSampleData(ByteBuffer.allocate(64 * 1024), 0) <= 0) {
                throw IOException("录音过短或不完整")
            }
            return duration
        } catch (failure: RuntimeException) {
            throw IOException("录音无法读取，请检查音频文件", failure)
        } finally {
            extractor.release()
        }
    }

    companion object {
        private var instance: LocalAudioOriginals? = null

        @Synchronized
        fun get(context: Context): LocalAudioOriginals = instance ?: LocalAudioOriginals(
            context.applicationContext,
        ).also { instance = it }
    }
}
