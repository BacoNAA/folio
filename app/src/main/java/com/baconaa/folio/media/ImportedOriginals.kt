package com.baconaa.folio.media

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.baconaa.folio.records.ContentItem
import com.baconaa.folio.records.ImportType
import com.baconaa.folio.records.newItemId
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@JvmInline
value class PdfId(val value: UUID)

internal class ImportedOriginals(private val context: Context, private val audio: LocalAudioOriginals) {
    private fun staging(id: UUID) = File(context.filesDir, "media-imports/$id.part")
    private fun pdf(id: PdfId) = File(context.filesDir, "pdf-originals/${id.value}.pdf")
    private fun original(id: UUID, type: ImportType) = when (type) {
        ImportType.PDF -> pdf(PdfId(id))
        ImportType.AUDIO -> audio.importedFile(AudioId(id))
    }

    /** 调用前已在 Room 登记清理候选；私有文件名只取本次生成的 UUID。 */
    suspend fun copy(source: Uri, type: ImportType, id: UUID): ContentItem {
        val temp = staging(id)
        Files.createDirectories(temp.toPath().parent)
        val size = copySource(source, temp)
        val item = when (type) {
            ImportType.PDF -> {
                inspectPdf(temp)
                ContentItem.Pdf(newItemId(), PdfId(id), displayName(source), size)
            }
            ImportType.AUDIO -> ContentItem.Audio(newItemId(), AudioId(id), audio.inspectDuration(temp))
        }
        currentCoroutineContext().ensureActive()
        val target = original(id, type).toPath()
        Files.createDirectories(target.parent)
        Files.move(temp.toPath(), target, ATOMIC_MOVE)
        return item
    }

    private suspend fun copySource(source: Uri, to: File): Long {
        val coroutine = currentCoroutineContext()
        val asset = try {
            context.contentResolver.openAssetFileDescriptor(source, "r")
        } catch (failure: FileNotFoundException) {
            throw IOException("来源文件已不存在或无法读取，请重新选择", failure)
        } catch (failure: SecurityException) {
            throw IOException("来源文件的读取授权已失效，请重新选择", failure)
        } ?: throw IOException("无法打开来源文件，请重新选择")
        return asset.use {
            val expected = it.length
            val size = it.createInputStream().use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    output.fd.sync()
                    copied
                }
            }
            if (size == 0L || (expected >= 0 && size != expected)) throw IOException("来源文件为空或读取不完整，请重新选择")
            size
        }
    }

    private fun displayName(source: Uri): String {
        val name = if (source.scheme == "content") {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && it.moveToFirst()) it.getString(column) else null
            }
        } else source.lastPathSegment
        return name?.takeIf { it.isNotBlank() } ?: "附件.pdf"
    }

    private fun inspectPdf(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            try {
                PdfRenderer(descriptor).use { document ->
                    if (document.pageCount == 0) throw IOException("PDF 没有可读取的页面")
                    document.openPage(0).close()
                }
            } catch (failure: SecurityException) {
                throw IOException("无法打开受保护的 PDF，请选择普通 PDF", failure)
            } catch (failure: IllegalArgumentException) {
                throw IOException("文件不是可读取的 PDF，请重新选择", failure)
            } catch (failure: IOException) {
                throw IOException("PDF 无法读取，请检查文件并重新选择", failure)
            }
        }
    }

    fun verify(item: ContentItem.Pdf) {
        val file = pdf(item.pdfId)
        if (file.length() != item.sizeBytes) throw IOException("PDF 原件缺失或已改变，请重新导入")
        inspectPdf(file)
    }

    fun openPdf(id: PdfId) = pdf(id).inputStream()

    fun viewIntent(item: ContentItem.Pdf): Intent {
        val file = pdf(item.pdfId)
        if (!file.isFile || !file.canRead()) throw IOException("PDF 原件无法读取")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.pdf", file, item.displayName)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun delete(id: UUID, type: ImportType) {
        Files.deleteIfExists(staging(id).toPath())
        Files.deleteIfExists(original(id, type).toPath())
    }
}
