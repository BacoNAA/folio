package com.baconaa.folio.records

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

data class DraftPreview(val id: DraftId, val excerpt: String)

data class RecordPreview(val id: RecordId, val excerpt: String)

/** 存储操作失败时向调用方抛出异常。 */
interface TextDrafts {
    /** 新建传 null，成功落盘后返回 ID。新建且内容为空时返回 null。 */
    suspend fun saveDraft(id: DraftId?, edit: TextEdit): DraftId?

    /** 仅列出可续写草稿，按最后编辑时间降序。 */
    suspend fun listDrafts(): List<DraftPreview>

    /** 返回上次成功保存的内容及位置，已移除的草稿返回 null。 */
    suspend fun openDraft(id: DraftId): TextEdit?

    /**
     * 最终输入写入正式记录、本次草稿移除须一起成功。
     * 失败保留草稿与原正式版本，重试不得重复创建正式记录。
     */
    suspend fun completeDraft(id: DraftId, finalEdit: TextEdit): RecordId
}

/** 使用同一正式记录 ID 查找和打开最后完成的版本。读取失败须向调用方抛出异常。 */
interface PublishedTextRecords {
    /** 按正文匹配，排除草稿与回收站。没有匹配时返回空列表。 */
    suspend fun search(keyword: String): List<RecordPreview>

    /** 返回完整的正式正文，记录不在有效正式记录中时返回 null。 */
    suspend fun openRecord(id: RecordId): String?
}
