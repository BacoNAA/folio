package com.baconaa.folio.records

import androidx.room.ColumnInfo

data class RecordMarks(
    @ColumnInfo(defaultValue = "0") val isStarred: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
)

/** 标记独立保存在正式记录上，立即生效，不改变内容修改时间或编辑版本。 */
interface RecordMarking {
    /** 重复设置保持同一结果；不存在或在回收站中的记录拒绝修改。 */
    suspend fun setStarred(id: RecordId, starred: Boolean)
    suspend fun setPinned(id: RecordId, pinned: Boolean)
}
