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

/** pendingPhotoCount 为 null 时清理暂不可用，记录删除结果仍以 deleted 为准。 */
data class PermanentDeletionResult(val deleted: Boolean, val pendingPhotoCount: Int?)

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

    /**
     * 页面确认后调用，仅允许彻底删除回收站记录及其草稿；有效记录会拒绝删除。
     * 重复调用 deleted 为 false。文件清理失败不恢复已删记录，待清理数在结果中返回。
     */
    suspend fun deletePermanently(id: RecordId): PermanentDeletionResult

    /** 重试此前中断或失败的原图清理，返回仍待清理的数量；不扫描未绑定的导入文件。 */
    suspend fun retryPhotoCleanup(): Int
}
