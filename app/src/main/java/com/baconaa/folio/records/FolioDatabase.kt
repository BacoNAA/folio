package com.baconaa.folio.records

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.DatabaseView
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
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
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)

@Entity(
    tableName = "text_record_edits",
    foreignKeys = [ForeignKey(
        entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordId")],
)
internal data class RecordEditRow(@PrimaryKey val draftId: String, val recordId: String, val baseRevision: Long)

@DatabaseView(
    """SELECT d.*, d.rowid AS insertionOrder, e.recordId FROM text_drafts d
       LEFT JOIN text_record_edits e ON e.draftId = d.id
       LEFT JOIN text_records r ON r.id = e.recordId
       WHERE r.deletedAt IS NULL""",
    viewName = "editable_text_drafts",
)
internal data class EditableDraftRow(
    @Embedded val draft: DraftRow,
    val insertionOrder: Long,
    val recordId: String?,
)

internal data class TextPreviewRow(val id: String, val excerpt: String)
internal data class DraftPreviewRow(val id: String, val excerpt: String, val recordId: String?)

@Dao
internal interface TextDao {
    @Insert
    suspend fun insertDraft(row: DraftRow)

    @Query("""UPDATE text_drafts SET body = :body, cursor = :cursor, lastEditedAt = :editedAt
              WHERE id = :id AND EXISTS (SELECT 1 FROM editable_text_drafts WHERE id = :id)""")
    suspend fun updateDraft(id: String, body: String, cursor: Int, editedAt: Long): Int

    @Query("SELECT id, body, cursor, firstRecordedAt, lastEditedAt FROM editable_text_drafts WHERE id = :id")
    suspend fun draft(id: String): DraftRow?

    @Query("""SELECT id, substr(body, 1, 80) AS excerpt, recordId FROM editable_text_drafts
              ORDER BY lastEditedAt DESC, insertionOrder DESC""")
    suspend fun listDrafts(): List<DraftPreviewRow>

    @Query("SELECT id FROM editable_text_drafts WHERE recordId = :recordId")
    suspend fun activeEditId(recordId: String): String?

    @Insert
    suspend fun insertEdit(edit: RecordEditRow)

    @Query("SELECT * FROM text_record_edits WHERE draftId = :draftId")
    suspend fun recordEdit(draftId: String): RecordEditRow?

    @Query("DELETE FROM text_record_edits WHERE draftId = :draftId")
    suspend fun deleteEdit(draftId: String)

    @Query("SELECT * FROM text_records WHERE id = :id AND deletedAt IS NULL")
    suspend fun activeRecord(id: String): RecordRow?

    @Query("SELECT id FROM text_records WHERE sourceDraftId = :draftId")
    suspend fun completedRecordId(draftId: String): String?

    @Insert
    suspend fun insertRecord(row: RecordRow)

    @Query("DELETE FROM text_drafts WHERE id = :id AND EXISTS (SELECT 1 FROM editable_text_drafts WHERE id = :id)")
    suspend fun deleteDraft(id: String): Int

    @Query(
        """SELECT id, substr(body, 1, 80) AS excerpt FROM text_records
           WHERE deletedAt IS NULL AND instr(lower(body), lower(:keyword)) > 0
           ORDER BY firstRecordedAt DESC, rowid DESC""",
    )
    suspend fun searchRecords(keyword: String): List<TextPreviewRow>

    @Query("SELECT body FROM text_records WHERE id = :id AND deletedAt IS NULL")
    suspend fun recordBody(id: String): String?
}

@Database(
    entities = [DraftRow::class, RecordRow::class, RecordEditRow::class],
    views = [EditableDraftRow::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class FolioDatabase : RoomDatabase() {
    abstract fun textDao(): TextDao
}
