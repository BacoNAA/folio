package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "tags",
    foreignKeys = [ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["ownerDraftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ownerDraftId")],
)
internal data class TagRow(@PrimaryKey val id: String, val name: String, val ownerDraftId: String?)

@Entity(
    tableName = "draft_tags", primaryKeys = ["draftId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagRow::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
internal data class DraftTagRow(val draftId: String, val tagId: String, val position: Int)

@Entity(
    tableName = "record_tags", primaryKeys = ["recordId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagRow::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
internal data class RecordTagRow(val recordId: String, val tagId: String, val position: Int)

internal data class RecordTagPreviewRow(val recordId: String, @Embedded val tag: TagRow)

// 两种搜索入口共用当前正式内容规则；参数使用 instr，用户输入的 %/_ 不会被当作通配符。
internal const val RECORD_TEXT_MATCH = """(instr(lower(r.body), lower(:keyword)) > 0
    OR instr(lower(r.title), lower(:keyword)) > 0
    OR (r.eventKind = 'description' AND instr(lower(r.eventText), lower(:keyword)) > 0)
    OR EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND instr(lower(i.caption), lower(:keyword)) > 0)
    OR EXISTS(SELECT 1 FROM record_tags rt JOIN tags t ON t.id = rt.tagId
              WHERE rt.recordId = r.id AND t.ownerDraftId IS NULL AND instr(lower(t.name), lower(:keyword)) > 0)
    OR EXISTS(SELECT 1 FROM record_places rp JOIN places p ON p.id = rp.placeId
              WHERE rp.recordId = r.id AND p.ownerDraftId IS NULL AND instr(lower(p.name), lower(:keyword)) > 0)
    OR EXISTS(SELECT 1 FROM published_objects po JOIN record_objects o ON o.id = po.objectId
              WHERE po.recordId = r.id AND o.ownerDraftId IS NULL AND (instr(lower(o.name), lower(:keyword)) > 0
              OR EXISTS(SELECT 1 FROM object_aliases a WHERE a.objectId = o.id AND instr(lower(a.name), lower(:keyword)) > 0))))"""

@Dao
internal abstract class TagDao {
    @Insert
    abstract suspend fun insertTag(tag: TagRow)

    @Query("SELECT * FROM tags WHERE id = :id")
    abstract suspend fun tag(id: String): TagRow?

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    abstract suspend fun tags(ids: List<String>): List<TagRow>

    @Query("SELECT * FROM tags WHERE ownerDraftId IS NULL AND instr(lower(name), lower(:keyword)) > 0 ORDER BY name COLLATE NOCASE, id")
    abstract suspend fun sharedTags(keyword: String): List<TagRow>

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: String, name: String)

    @Query("SELECT t.* FROM draft_tags dt JOIN tags t ON t.id = dt.tagId WHERE dt.draftId = :id ORDER BY dt.position")
    abstract suspend fun draftTags(id: String): List<TagRow>

    @Query("SELECT t.* FROM record_tags rt JOIN tags t ON t.id = rt.tagId WHERE rt.recordId = :id ORDER BY rt.position")
    abstract suspend fun recordTags(id: String): List<TagRow>

    @Query("""SELECT rt.recordId, t.* FROM record_tags rt JOIN tags t ON t.id = rt.tagId
              JOIN text_records r ON r.id = rt.recordId WHERE r.deletedAt IS NULL AND rt.recordId IN (:ids)
              ORDER BY rt.recordId, rt.position""")
    abstract suspend fun activeRecordTags(ids: List<String>): List<RecordTagPreviewRow>

    @Query("""UPDATE tags SET ownerDraftId = NULL WHERE ownerDraftId = :draftId
              AND id IN (SELECT tagId FROM draft_tags WHERE draftId = :draftId)""")
    abstract suspend fun promoteSelected(draftId: String)

    @Transaction
    open suspend fun replaceDraftTags(id: String, tags: List<String>) {
        deleteDraftTags(id)
        insertDraftTags(tags.mapIndexed { position, tag -> DraftTagRow(id, tag, position) })
    }

    @Transaction
    open suspend fun replaceRecordTags(id: String, tags: List<String>) {
        deleteRecordTags(id)
        insertRecordTags(tags.mapIndexed { position, tag -> RecordTagRow(id, tag, position) })
    }

    @Query("DELETE FROM draft_tags WHERE draftId = :id")
    protected abstract suspend fun deleteDraftTags(id: String)

    @Query("DELETE FROM record_tags WHERE recordId = :id")
    protected abstract suspend fun deleteRecordTags(id: String)

    @Insert
    protected abstract suspend fun insertDraftTags(tags: List<DraftTagRow>)

    @Insert
    protected abstract suspend fun insertRecordTags(tags: List<RecordTagRow>)
}
