package com.baconaa.folio.records

import android.content.Intent
import android.net.Uri
import com.baconaa.folio.media.PdfId
import java.io.InputStream

enum class ImportType { PDF, AUDIO }

data class ImportedItem(val draftId: DraftId, val item: ContentItem)

interface MediaImports {
    /** 选择成功后调用。after 为空时追加；只有原件与草稿项都落盘后才返回。 */
    suspend fun importMedia(
        source: Uri, type: ImportType, draftId: DraftId? = null, after: ItemId? = null,
        initialEventTime: EventTime? = null,
    ): ImportedItem

    /** 从 Folio 副本读取，调用方在 I/O 线程使用并关闭流。 */
    fun openPdf(id: PdfId): InputStream

    /** 仅授予选中的查看器读取权限。调用方负责启动及处理缺少查看器的情况。 */
    fun pdfViewIntent(item: ContentItem.Pdf): Intent
}
