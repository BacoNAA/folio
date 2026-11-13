package com.baconaa.folio.records

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RenameTable
import androidx.room.Transaction
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** itemId 标识这一次插入；不同照片项可以共用原图，各自保留自己的说明。 */
internal data class ContentSlot(
    val itemId: String,
    val text: String?,
    val photoId: String?,
    @ColumnInfo(defaultValue = "''") val caption: String = "",
)

@Entity(
    tableName = "draft_items",
    primaryKeys = ["draftId", "position"],
    indices = [Index(value = ["draftId", "itemId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class DraftItemRow(val draftId: String, val position: Int, @Embedded val slot: ContentSlot)

@Entity(
    tableName = "record_items",
    primaryKeys = ["recordId", "position"],
    indices = [Index(value = ["recordId", "itemId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class RecordItemRow(val recordId: String, val position: Int, @Embedded val slot: ContentSlot)

@Dao
internal abstract class ContentItemDao {
    @Query("SELECT itemId, text, photoId, caption FROM draft_items WHERE draftId = :id ORDER BY position")
    abstract suspend fun draftItems(id: String): List<ContentSlot>

    @Query("SELECT itemId, text, photoId, caption FROM record_items WHERE recordId = :id ORDER BY position")
    abstract suspend fun recordItems(id: String): List<ContentSlot>

    @Transaction
    open suspend fun replaceDraftItems(id: String, items: List<ContentSlot>) {
        deleteDraftItems(id)
        insertDraftRows(items.mapIndexed { position, item -> DraftItemRow(id, position, item) })
    }

    @Transaction
    open suspend fun replaceRecordItems(id: String, items: List<ContentSlot>) {
        deleteRecordItems(id)
        insertRecordRows(items.mapIndexed { position, item -> RecordItemRow(id, position, item) })
    }

    @Insert
    protected abstract suspend fun insertDraftRows(rows: List<DraftItemRow>)

    @Insert
    protected abstract suspend fun insertRecordRows(rows: List<RecordItemRow>)

    @Query("DELETE FROM draft_items WHERE draftId = :id")
    protected abstract suspend fun deleteDraftItems(id: String)

    @Query("DELETE FROM record_items WHERE recordId = :id")
    protected abstract suspend fun deleteRecordItems(id: String)
}

@RenameTable(fromTableName = "draft_photos", toTableName = "draft_items")
@RenameTable(fromTableName = "record_photos", toTableName = "record_items")
internal class MixedContentMigration : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        // v3 的正文在所有照片之前。-1 让旧照片原位保留，之后编辑时按当前列表顺序重排。
        connection.execSQL(
            """INSERT INTO record_items(recordId, position, itemId, text)
               SELECT id, -1, 'body:' || id, body FROM text_records WHERE body <> ''""",
        )
        connection.execSQL(
            """INSERT INTO draft_items(draftId, position, itemId, text)
               SELECT d.id, -1, 'body:' || COALESCE(e.recordId, d.id), d.body FROM text_drafts d
               LEFT JOIN text_record_edits e ON e.draftId = d.id WHERE d.body <> ''""",
        )
        connection.execSQL(
            """UPDATE text_drafts SET activeItemId =
               (SELECT itemId FROM draft_items WHERE draftId = text_drafts.id ORDER BY position LIMIT 1)""",
        )
    }
}
