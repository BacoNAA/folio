package com.baconaa.folio.records

import com.baconaa.folio.media.PhotoId

@JvmInline
value class DraftId(val value: String)

@JvmInline
value class RecordId(val value: String)

/** 光标使用与 Android 文本框一致的 UTF-16 偏移，可停在正文末尾。 */
data class TextEdit(val text: String, val cursor: Int) {
    init {
        require(cursor in 0..text.length) { "编辑位置超出正文范围" }
    }
}

data class DraftPreview(
    val id: DraftId,
    val excerpt: String,
    val recordId: RecordId? = null,
    val firstPhotoId: PhotoId? = null,
    /** 已保存录音项的总时长；无录音时为 null，可用于辨认纯录音草稿。 */
    val totalAudioDurationMillis: Long? = null,
    val title: String? = null,
    val splitSource: RecordId? = null,
)

data class RecordPreview(
    val id: RecordId, val excerpt: String, val firstPhotoId: PhotoId? = null,
    val marks: RecordMarks = RecordMarks(),
    val title: String? = null,
)

data class RecordContent(
    val items: List<ContentItem>, val times: RecordTimes, val marks: RecordMarks = RecordMarks(),
    val tags: List<Tag> = emptyList(),
    val place: Place? = null,
    val objects: List<RecordObject> = emptyList(),
    val title: String? = null,
    val revision: Long = 1,
    val templateFields: List<TemplateField> = items.mapNotNull { it.templateField() },
    val feelings: List<NamedFeeling> = emptyList(),
    val templateScaleIds: Set<ScaleId> = emptySet(),
) {
    val text: String get() = items.body()
    val photos: List<PhotoId> get() = items.photos()
}

/** 存储操作失败时向调用方抛出异常。 */
interface TextDrafts {
    /** 可先写标题起草；新建时全空返回 null。清空只剩标题的草稿时也返回 null。 */
    suspend fun saveDraftTitle(id: DraftId?, title: String?, initialEventTime: EventTime? = null): DraftId?

    /** 按传入顺序追加已导入的原图。id 为 null 时新建，并采用已确认的 initialEventTime。 */
    suspend fun appendPhotos(id: DraftId?, photos: List<PhotoId>, initialEventTime: EventTime? = null): DraftId

    /** 返回草稿中的原图 ID，草稿不存在或不可续写时返回 null。 */
    suspend fun openDraftPhotos(id: DraftId): List<PhotoId>?

    /** 开始修改有效正式记录；已有编辑草稿时继续同一份。 */
    suspend fun editRecord(id: RecordId): DraftId

    /** 按预览的正式版本和顺序建立独立草稿。来源失效或版本变化时抛出 CopySourceChanged。 */
    suspend fun copyRecordToDraft(source: RecordId, expectedRevision: Long, selection: RecordCopySelection): DraftId

    /** 核对预览的全部正式版本，从至少两条来源选取实际内容，在同一事务中建立独立草稿。 */
    suspend fun mergeRecordsToDraft(expectedRevisions: Map<RecordId, Long>, selection: RecordMergeSelection): DraftId

    /** 建立可续写的拆分草稿；完成该草稿时才从指定来源移出仍被选中的实际内容。 */
    suspend fun splitRecordToDraft(source: RecordId, expectedRevision: Long, selection: RecordCopySelection): DraftId

    /** 放弃新建或编辑草稿，保留正式记录。重复放弃不会影响后来建立的草稿。 */
    suspend fun discardDraft(id: DraftId)

    /**
     * 新建传 null，成功落盘后返回 ID。新建且内容为空时返回 null。
     * initialEventTime 仅用于新建，现场默认值由调用方传入；未填不推断为现在。
     */
    suspend fun saveDraft(id: DraftId?, edit: TextEdit, initialEventTime: EventTime? = null): DraftId?

    /** 仅列出可续写草稿，按最后编辑时间降序。 */
    suspend fun listDrafts(): List<DraftPreview>

    /** 返回上次成功保存的内容及位置，已移除的草稿返回 null。 */
    suspend fun openDraft(id: DraftId): TextEdit?

    /**
     * 最终输入写入正式记录、本次草稿移除须一起成功。
     * 标题、混合内容、事件时间和线索一起提交，至少要有一项实际内容。
     * 编辑草稿更新原记录并返回同一 ID。内容及归属未变时保留修改时间。
     * 失败保留草稿与原正式版本，重复完成返回已提交的 ID。
     */
    suspend fun completeDraft(id: DraftId, finalEdit: TextEdit): RecordId
}

/** 使用同一正式记录 ID 查找和打开最后完成的版本。读取失败须向调用方抛出异常。 */
interface PublishedTextRecords {
    /** 读取完整正式内容、时间、标记及标签当前名称；不可查看的记录返回 null。 */
    suspend fun openRecordContent(id: RecordId): RecordContent?

    /** 匹配上次完成的标题、正文、清单、文件名、媒体说明、网址与个人备注及支持的线索。排除草稿与回收站。 */
    suspend fun search(keyword: String): List<RecordPreview>

    /** 返回完整的正式正文，记录不在有效正式记录中时返回 null。 */
    suspend fun openRecord(id: RecordId): String?
}
