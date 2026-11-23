package com.baconaa.folio.records

import android.content.Intent
import android.net.Uri
import com.baconaa.folio.media.PdfId
import java.io.IOException
import java.io.InputStream

enum class ImportType { PDF, AUDIO }

/** 可直接展示给导入者的原因；底层系统异常保留在 cause 中。 */
class MediaImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ImportedItem(val draftId: DraftId, val item: ContentItem)

interface MediaImports {
    /** 选择成功后调用。after 为空时追加。初始时间和地点仅用于以文件起草，原件与草稿项均落盘后返回。 */
    suspend fun importMedia(
        source: Uri, type: ImportType, draftId: DraftId? = null, after: ItemId? = null,
        initialEventTime: EventTime? = null, initialPlaceId: PlaceId? = null,
    ): ImportedItem

    /** 从 Folio 副本读取，调用方在 I/O 线程使用并关闭流。 */
    fun openPdf(id: PdfId): InputStream

    /** 仅授予选中的查看器读取权限。调用方负责启动及处理缺少查看器的情况。 */
    fun pdfViewIntent(item: ContentItem.Pdf): Intent
}
