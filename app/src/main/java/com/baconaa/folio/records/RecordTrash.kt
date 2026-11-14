package com.baconaa.folio.records

import com.baconaa.folio.media.PhotoId

data class TrashPreview(
    val id: RecordId,
    val excerpt: String,
    val firstPhotoId: PhotoId?,
    val deletedAt: Long,
    val hasDraft: Boolean,
)

data class TrashedRecord(
    val id: RecordId,
    val content: RecordContent,
    val deletedAt: Long,
    val hasDraft: Boolean,
)

/** 回收站只读展示最后完成的内容；编辑草稿随正式记录一起隐藏和恢复。 */
interface RecordTrash {
    /** 已删除或不存在时返回 false；保留内容、草稿、照片和内容修改时间。 */
    suspend fun moveToTrash(id: RecordId): Boolean

    /** 立即撤销与回收站恢复共用此操作；不自动完成草稿。未在回收站时返回 false。 */
    suspend fun restoreRecord(id: RecordId): Boolean

    /** 按删除时间降序排列，hasDraft 表示尚有未完成修改。 */
    suspend fun listTrash(): List<TrashPreview>

    /** 读取正式版本和草稿存在标记；记录未在回收站时返回 null。 */
    suspend fun openTrashRecord(id: RecordId): TrashedRecord?
}
