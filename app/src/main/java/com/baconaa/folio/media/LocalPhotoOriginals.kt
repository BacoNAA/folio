package com.baconaa.folio.media

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@JvmInline
value class PhotoId(val value: UUID)

class LocalPhotoOriginals private constructor(private val context: Context) {
    private val originals get() = File(context.filesDir, "photo-originals")

    private val pending by lazy {
        val directory = Files.createDirectories(File(originals, ".pending").toPath()).toFile()
        // The first import in a new process can remove unfinished copies from the previous one.
        directory.listFiles()?.forEach { it.delete() }
        directory
    }

    /** 从拍照或选图返回的 URI 复制原图，确认本地副本可读后才返回可持久保存的 ID。 */
    suspend fun importPhoto(source: Uri): PhotoId = withContext(Dispatchers.IO) {
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
            val id = PhotoId(UUID.randomUUID())
            Files.move(staging.toPath(), original(id).toPath(), ATOMIC_MOVE)
            id
        } finally {
            staging.delete()
        }
    }

    /** 在 I/O 线程读取，调用方负责关闭返回的流。 */
    fun openOriginal(id: PhotoId): InputStream = original(id).inputStream()

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
