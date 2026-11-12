package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "text_drafts")
internal data class DraftRow(
    @PrimaryKey val id: String,
    val body: String,
    val cursor: Int,
    val firstRecordedAt: Long,
    val lastEditedAt: Long,
)

@Entity(tableName = "text_records", indices = [Index(value = ["sourceDraftId"], unique = true)])
internal data class RecordRow(
    @PrimaryKey val id: String,
    val sourceDraftId: String,
    val body: String,
    val firstRecordedAt: Long,
    val modifiedAt: Long,
    val deletedAt: Long? = null,
)

internal data class TextPreviewRow(val id: String, val excerpt: String)

@Dao
internal interface TextDao {
    @Insert
    suspend fun insertDraft(row: DraftRow)

    @Query("UPDATE text_drafts SET body = :body, cursor = :cursor, lastEditedAt = :editedAt WHERE id = :id")
    suspend fun updateDraft(id: String, body: String, cursor: Int, editedAt: Long): Int

    @Query("SELECT * FROM text_drafts WHERE id = :id")
    suspend fun draft(id: String): DraftRow?

    @Query("SELECT id, substr(body, 1, 80) AS excerpt FROM text_drafts ORDER BY lastEditedAt DESC, rowid DESC")
    suspend fun listDrafts(): List<TextPreviewRow>

    @Query("SELECT id FROM text_records WHERE sourceDraftId = :draftId")
    suspend fun completedRecordId(draftId: String): String?

    @Insert
    suspend fun insertRecord(row: RecordRow)

    @Query("DELETE FROM text_drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)
}

@Database(entities = [DraftRow::class, RecordRow::class], version = 1)
internal abstract class FolioDatabase : RoomDatabase() {
    abstract fun textDao(): TextDao
}
