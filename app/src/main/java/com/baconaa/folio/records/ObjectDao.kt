package com.baconaa.folio.records

import androidx.room.*

@Entity(tableName = "record_objects", foreignKeys = [ForeignKey(entity = DraftRow::class,
    parentColumns = ["id"], childColumns = ["ownerDraftId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ownerDraftId")])
internal data class ObjectRow(@PrimaryKey val id: String, val kind: String, val name: String, val note: String, val ownerDraftId: String?)

@Entity(tableName = "object_aliases", primaryKeys = ["objectId", "name"], foreignKeys = [ForeignKey(entity = ObjectRow::class,
    parentColumns = ["id"], childColumns = ["objectId"], onDelete = ForeignKey.CASCADE)])
internal data class ObjectAliasRow(val objectId: String, val name: String, val position: Int)

@Entity(tableName = "draft_objects", primaryKeys = ["draftId", "objectId"], foreignKeys = [
    ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ObjectRow::class, parentColumns = ["id"], childColumns = ["objectId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("objectId")])
internal data class DraftObjectRow(val draftId: String, val objectId: String, val position: Int)

@Entity(tableName = "published_objects", primaryKeys = ["recordId", "objectId"], foreignKeys = [
    ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ObjectRow::class, parentColumns = ["id"], childColumns = ["objectId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("objectId")])
internal data class PublishedObjectRow(val recordId: String, val objectId: String, val position: Int)

internal data class ObjectDetails(@Embedded val row: ObjectRow,
    @Relation(parentColumn = "id", entityColumn = "objectId") val aliases: List<ObjectAliasRow>)
internal data class RecordObjectPreviewRow(val recordId: String, @Embedded val details: ObjectDetails)

@Dao
internal abstract class ObjectDao {
    @Insert abstract suspend fun insert(row: ObjectRow)
    @Update abstract suspend fun update(row: ObjectRow)
    @Transaction @Query("SELECT * FROM record_objects WHERE id = :id")
    abstract suspend fun objectById(id: String): ObjectDetails?
    @Query("SELECT * FROM record_objects WHERE id IN (:ids)") abstract suspend fun objects(ids: List<String>): List<ObjectRow>
    @Transaction @Query("SELECT * FROM record_objects WHERE ownerDraftId IS NULL ORDER BY kind, name COLLATE NOCASE, id")
    abstract suspend fun sharedObjects(): List<ObjectDetails>
    @Transaction @Query("SELECT o.* FROM draft_objects d JOIN record_objects o ON o.id = d.objectId WHERE d.draftId = :id ORDER BY d.position")
    abstract suspend fun draftObjects(id: String): List<ObjectDetails>
    @Transaction @Query("SELECT o.* FROM published_objects p JOIN record_objects o ON o.id = p.objectId WHERE p.recordId = :id ORDER BY p.position")
    abstract suspend fun recordObjects(id: String): List<ObjectDetails>
    @Transaction @Query("""SELECT p.recordId, o.* FROM published_objects p JOIN record_objects o ON o.id = p.objectId
        JOIN text_records r ON r.id = p.recordId WHERE r.deletedAt IS NULL AND p.recordId IN (:ids)
        ORDER BY p.recordId, p.position""")
    abstract suspend fun activeRecordObjects(ids: List<String>): List<RecordObjectPreviewRow>
    @Query("""UPDATE record_objects SET ownerDraftId = NULL WHERE ownerDraftId = :id
        AND id IN (SELECT objectId FROM draft_objects WHERE draftId = :id)""")
    abstract suspend fun promoteSelected(id: String)
    @Transaction open suspend fun replaceDraftObjects(id: String, ids: List<String>) {
        deleteDraftObjects(id)
        insertDraftObjects(ids.mapIndexed { position, objectId -> DraftObjectRow(id, objectId, position) })
    }
    @Transaction open suspend fun replaceRecordObjects(id: String, ids: List<String>) {
        deleteRecordObjects(id)
        insertRecordObjects(ids.mapIndexed { position, objectId -> PublishedObjectRow(id, objectId, position) })
    }
    @Transaction open suspend fun replaceAliases(id: String, aliases: List<String>) {
        deleteAliases(id)
        insertAliases(aliases.mapIndexed { position, name -> ObjectAliasRow(id, name, position) })
    }
    @Query("DELETE FROM draft_objects WHERE draftId = :id") protected abstract suspend fun deleteDraftObjects(id: String)
    @Query("DELETE FROM published_objects WHERE recordId = :id") protected abstract suspend fun deleteRecordObjects(id: String)
    @Query("DELETE FROM object_aliases WHERE objectId = :id") protected abstract suspend fun deleteAliases(id: String)
    @Insert protected abstract suspend fun insertDraftObjects(rows: List<DraftObjectRow>)
    @Insert protected abstract suspend fun insertRecordObjects(rows: List<PublishedObjectRow>)
    @Insert protected abstract suspend fun insertAliases(rows: List<ObjectAliasRow>)
}
