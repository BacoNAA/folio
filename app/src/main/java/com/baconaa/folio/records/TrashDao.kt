package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Query

internal data class TrashPreviewRow(
    val id: String,
    val excerpt: String,
    val firstPhotoId: String?,
    val deletedAt: Long,
    val hasDraft: Boolean,
)

@Dao
internal interface TrashDao {
    @Query("UPDATE text_records SET deletedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL")
    suspend fun moveToTrash(id: String, deletedAt: Long): Int

    @Query("UPDATE text_records SET deletedAt = NULL WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restore(id: String): Int

    @Query("""SELECT r.id, substr(r.body, 1, 80) AS excerpt, r.deletedAt,
              (SELECT photoId FROM record_items WHERE recordId = r.id AND photoId IS NOT NULL ORDER BY position LIMIT 1) AS firstPhotoId,
              EXISTS(SELECT 1 FROM text_record_edits e JOIN text_drafts d ON d.id = e.draftId WHERE e.recordId = r.id) AS hasDraft
              FROM text_records r WHERE r.deletedAt IS NOT NULL
              ORDER BY r.deletedAt DESC, r.rowid DESC""")
    suspend fun listTrash(): List<TrashPreviewRow>

    @Query("SELECT * FROM text_records WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun trashedRecord(id: String): RecordRow?

    @Query("""SELECT EXISTS(SELECT 1 FROM text_record_edits e
              JOIN text_drafts d ON d.id = e.draftId WHERE e.recordId = :id)""")
    suspend fun hasDraft(id: String): Boolean

    @Query("""SELECT d.id FROM text_drafts d JOIN text_record_edits e ON e.draftId = d.id
              WHERE e.recordId = :id""")
    suspend fun editingDraftIds(id: String): List<String>

    // 隐藏草稿不能走普通放弃入口；必须在删父记录及其关联之前删除。
    @Query("DELETE FROM text_drafts WHERE id IN (SELECT draftId FROM text_record_edits WHERE recordId = :id)")
    suspend fun deleteEditingDrafts(id: String)

    @Query("DELETE FROM text_records WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun deletePermanently(id: String): Int
}
