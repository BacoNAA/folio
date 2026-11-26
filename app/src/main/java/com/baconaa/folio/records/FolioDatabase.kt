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
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Entity(tableName = "text_drafts")
internal data class DraftRow(
    @PrimaryKey val id: String,
    val body: String,
    val cursor: Int,
    // v15 的物理列保留原位；标题可以先于第一份实际内容创建草稿。
    @ColumnInfo(name = "firstRecordedAt") val createdAt: Long,
    val lastEditedAt: Long,
    val activeItemId: String? = null,
    @Embedded val eventTime: EventTimeFields? = null,
    val title: String? = null,
    val firstContentAt: Long? = null,
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
    @Embedded val eventTime: EventTimeFields? = null,
    @Embedded val marks: RecordMarks = RecordMarks(),
    val title: String? = null,
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

/** 拆分预览选中的原项和对应草稿项。草稿移除某项后再撤销，仍可找回它对应的原项。 */
@Entity(
    tableName = "record_split_items",
    primaryKeys = ["draftId", "sourceItemId"],
    foreignKeys = [ForeignKey(
        entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class RecordSplitItemRow(val draftId: String, val sourceItemId: String, val draftItemId: String)

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

internal data class TextPreviewRow(
    val id: String, val excerpt: String, val firstPhotoId: String?, @Embedded val marks: RecordMarks,
    val title: String?,
)
internal data class DraftPreviewRow(
    val id: String, val excerpt: String, val recordId: String?, val firstPhotoId: String?,
    val totalAudioDurationMillis: Long?,
    val title: String?,
    val isSplit: Boolean,
)

@Dao
internal interface TextDao {
    @Insert
    suspend fun insertDraft(row: DraftRow)

    @Query("""UPDATE text_drafts SET body = :body, cursor = :cursor, activeItemId = :activeItemId,
              lastEditedAt = :editedAt, firstContentAt = COALESCE(firstContentAt, :contentAt)
              WHERE id = :id AND EXISTS (SELECT 1 FROM editable_text_drafts WHERE id = :id)""")
    suspend fun updateDraft(id: String, body: String, cursor: Int, activeItemId: String?, editedAt: Long, contentAt: Long?): Int

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM editable_text_drafts WHERE id = :id")
    suspend fun draft(id: String): DraftRow?

    @Update
    suspend fun updateDraft(row: DraftRow)

    @Query("UPDATE text_drafts SET lastEditedAt = :editedAt WHERE id = :id")
    suspend fun touchDraft(id: String, editedAt: Long)

    @Query("""SELECT d.id, d.title, substr(d.body, 1, 80) AS excerpt, d.recordId,
              EXISTS(SELECT 1 FROM record_split_items s WHERE s.draftId = d.id) AS isSplit,
              (SELECT photoId FROM draft_items WHERE draftId = d.id AND photoId IS NOT NULL ORDER BY position LIMIT 1) AS firstPhotoId,
              (SELECT sum(audioDurationMillis) FROM draft_items WHERE draftId = d.id) AS totalAudioDurationMillis
              FROM editable_text_drafts d
              ORDER BY lastEditedAt DESC, insertionOrder DESC""")
    suspend fun listDrafts(): List<DraftPreviewRow>

    @Query("SELECT id FROM editable_text_drafts WHERE recordId = :recordId")
    suspend fun activeEditId(recordId: String): String?

    @Insert
    suspend fun insertEdit(edit: RecordEditRow)

    @Query("SELECT itemId FROM draft_items WHERE draftId = :draftId ORDER BY position")
    suspend fun draftItemIds(draftId: String): List<String>

    @Insert
    suspend fun insertSplitItems(items: List<RecordSplitItemRow>)

    @Query("SELECT * FROM record_split_items WHERE draftId = :draftId")
    suspend fun splitItems(draftId: String): List<RecordSplitItemRow>

    @Query("""SELECT recordId FROM text_record_edits WHERE draftId = :draftId
              AND EXISTS (SELECT 1 FROM record_split_items s WHERE s.draftId = :draftId)""")
    suspend fun splitSource(draftId: String): String?

    @Query("SELECT * FROM text_record_edits WHERE draftId = :draftId")
    suspend fun recordEdit(draftId: String): RecordEditRow?

    @Query("DELETE FROM text_record_edits WHERE draftId = :draftId")
    suspend fun deleteEdit(draftId: String)

    @Query("SELECT * FROM text_records WHERE id = :id AND deletedAt IS NULL")
    suspend fun activeRecord(id: String): RecordRow?

    @Query("UPDATE text_records SET isStarred = :starred WHERE id = :id AND deletedAt IS NULL")
    suspend fun setStarred(id: String, starred: Boolean): Int

    @Query("UPDATE text_records SET isPinned = :pinned WHERE id = :id AND deletedAt IS NULL")
    suspend fun setPinned(id: String, pinned: Boolean): Int

    // An edit keeps its link after completion so older retries can find the record.
    // Discard removes both the draft and its link.
    @Query("""SELECT id FROM text_records WHERE sourceDraftId = :draftId AND deletedAt IS NULL
              UNION ALL
              SELECT e.recordId FROM text_record_edits e JOIN text_records r ON r.id = e.recordId
              WHERE e.draftId = :draftId AND r.deletedAt IS NULL
              AND NOT EXISTS (SELECT 1 FROM text_drafts WHERE id = :draftId)""")
    suspend fun completedRecordId(draftId: String): String?

    @Insert
    suspend fun insertRecord(row: RecordRow)

    @Update
    suspend fun updateRecord(row: RecordRow)

    @Query("DELETE FROM text_drafts WHERE id = :id AND EXISTS (SELECT 1 FROM editable_text_drafts WHERE id = :id)")
    suspend fun deleteDraft(id: String): Int

    @Query(
        """SELECT r.id, r.title, substr(r.body, 1, 80) AS excerpt, r.isStarred, r.isPinned,
           (SELECT photoId FROM record_items WHERE recordId = r.id AND photoId IS NOT NULL ORDER BY position LIMIT 1) AS firstPhotoId
           FROM text_records r
           WHERE deletedAt IS NULL AND """ + RECORD_TEXT_MATCH + " ORDER BY isPinned DESC, firstRecordedAt DESC, r.rowid DESC",
    )
    suspend fun searchRecords(keyword: String): List<TextPreviewRow>

    @Query("SELECT body FROM text_records WHERE id = :id AND deletedAt IS NULL")
    suspend fun recordBody(id: String): String?
}

@Database(
    entities = [
        DraftRow::class, RecordRow::class, RecordEditRow::class, RecordSplitItemRow::class,
        DraftItemRow::class, RecordItemRow::class, RemovedItem::class, PhotoCleanupEntry::class,
        AudioCleanupEntry::class, TagRow::class, DraftTagRow::class, RecordTagRow::class,
        PlaceRow::class, DraftPlaceRow::class, RecordPlaceRow::class,
        ObjectRow::class, ObjectAliasRow::class, DraftObjectRow::class, PublishedObjectRow::class,
        SeasonSettingRow::class, ImportCleanupEntry::class, GroupRow::class, GroupMemberRow::class,
        GuideSectionRow::class, GuideAssignmentRow::class, TemplateRow::class, TemplateFieldRow::class, TemplateTagRow::class,
    ],
    views = [EditableDraftRow::class],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = MixedContentMigration::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16, spec = DraftTitleMigration::class),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
    ],
)
@TypeConverters(PhotoStrokeStorage::class)
internal abstract class FolioDatabase : RoomDatabase() {
    abstract fun textDao(): TextDao
    abstract fun itemDao(): ContentItemDao
    abstract fun removedItemDao(): RemovedItemDao
    abstract fun trashDao(): TrashDao
    abstract fun groupDao(): GroupDao
    abstract fun guideDao(): GroupGuideDao
    abstract fun templateDao(): TemplateDao
    abstract fun photoCleanupDao(): PhotoCleanupDao
    abstract fun audioCleanupDao(): AudioCleanupDao
    abstract fun importCleanupDao(): ImportCleanupDao
    abstract fun recordBrowsingDao(): RecordBrowsingDao
    abstract fun tagDao(): TagDao
    abstract fun placeDao(): PlaceDao
    abstract fun objectDao(): ObjectDao
    abstract fun seasonSettingsDao(): SeasonSettingsDao
}

internal class DraftTitleMigration : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        // 旧版创建草稿时已有实际内容，即使后来移除了全部内容也保留首次记录时间。
        connection.execSQL("UPDATE text_drafts SET firstContentAt = firstRecordedAt")
    }
}
