package com.baconaa.folio.media

import android.content.Context
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@JvmInline
value class PhotoId(val value: UUID)

/** 照片中记载的拍摄当地时间；只有原始元数据提供偏移量时才可确定对应瞬间。 */
data class PhotoCaptureTime(val localDateTime: LocalDateTime, val offset: ZoneOffset?)

private val captureTimeFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

class LocalPhotoOriginals private constructor(private val context: Context) {
    private val originals get() = File(context.filesDir, "photo-originals")

    private val pending by lazy {
        val directory = Files.createDirectories(File(originals, ".pending").toPath()).toFile()
        // The first import in a new process can remove unfinished copies from the previous one.
        directory.listFiles()?.forEach { it.delete() }
        directory
    }

    /** 从拍照或选图返回的 URI 复制原图，确认本地副本可读后才返回可持久保存的 ID。 */
    suspend fun importPhoto(source: Uri): PhotoId = importPhoto(source, PhotoId(UUID.randomUUID()))

    internal suspend fun importPhoto(source: Uri, id: PhotoId): PhotoId = withContext(Dispatchers.IO) {
        val staging = File.createTempFile("photo-", ".part", pending)
        try {
            val asset = context.contentResolver.openAssetFileDescriptor(source, "r")
                ?: throw IOException("无法打开照片来源，请重新选择照片")
            asset.use {
                val expected = it.length
                val copied = it.createInputStream().use { input ->
                    staging.outputStream().use { output ->
                        val count = input.copyTo(output)
                        output.fd.sync()
                        count
                    }
                }
                if (expected >= 0 && copied != expected) throw IOException("照片读取不完整，请重新导入")
            }

            // Decode a small sample to verify readability. The saved bytes retain the original metadata.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(staging)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 128))
            }.recycle()
            ensureActive()
            Files.move(staging.toPath(), original(id).toPath(), ATOMIC_MOVE)
            id
        } finally {
            staging.delete()
        }
    }

    /** 在 I/O 线程读取，调用方负责关闭返回的流。 */
    fun openOriginal(id: PhotoId): InputStream = original(id).inputStream()

    /** 从已导入的原件读取时间供用户确认。无有效拍摄时间返回 null，原件本身无法打开时抛出 I/O 错误。 */
    suspend fun readCaptureTime(id: PhotoId): PhotoCaptureTime? = withContext(Dispatchers.IO) {
        openOriginal(id).use { input ->
            val exif = try { ExifInterface(input) } catch (_: IOException) { return@withContext null }
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return@withContext null
            val local = try {
                LocalDateTime.parse(raw.trim(), captureTimeFormat)
            } catch (_: DateTimeException) {
                return@withContext null
            }
            val fraction = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim().orEmpty()
            val nanos = if (fraction.isNotEmpty() && fraction.all { it in '0'..'9' }) {
                fraction.take(9).padEnd(9, '0').toInt()
            } else 0
            val offset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { value ->
                    try { ZoneOffset.of(value.trim()) } catch (_: DateTimeException) { null }
                }
            } else null
            PhotoCaptureTime(local.withNano(nanos), offset)
        }
    }

    /** 仅由引用清理在确认无引用后调用；已不存在视为清理成功。 */
    internal fun deleteOriginal(id: PhotoId) {
        Files.deleteIfExists(original(id).toPath())
    }

    private fun original(id: PhotoId) = File(originals, id.value.toString())

    companion object {
        private var instance: LocalPhotoOriginals? = null

        @Synchronized
        fun get(context: Context): LocalPhotoOriginals = instance ?: LocalPhotoOriginals(
            context.applicationContext,
        ).also { instance = it }
    }
}
