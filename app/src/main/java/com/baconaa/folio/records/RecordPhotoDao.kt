package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** itemId 标识这一次插入，photoId 标识可由多个内容项共用的原图。 */
internal data class PhotoSlot(val itemId: String, val photoId: String, val position: Int)

@Entity(
    tableName = "draft_photos",
    primaryKeys = ["draftId", "position"],
    indices = [Index(value = ["draftId", "itemId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class DraftPhotoRow(val draftId: String, @Embedded val slot: PhotoSlot)

@Entity(
    tableName = "record_photos",
    primaryKeys = ["recordId", "position"],
    indices = [Index(value = ["recordId", "itemId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class RecordPhotoRow(val recordId: String, @Embedded val slot: PhotoSlot)

@Dao
internal abstract class RecordPhotoDao {
    @Query("SELECT itemId, photoId, position FROM draft_photos WHERE draftId = :id ORDER BY position")
    abstract suspend fun draftPhotos(id: String): List<PhotoSlot>

    @Query("SELECT itemId, photoId, position FROM record_photos WHERE recordId = :id ORDER BY position")
    abstract suspend fun recordPhotos(id: String): List<PhotoSlot>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM draft_photos WHERE draftId = :id")
    abstract suspend fun nextDraftPosition(id: String): Int

    suspend fun insertDraftPhotos(id: String, photos: List<PhotoSlot>) {
        insertDraftRows(photos.map { DraftPhotoRow(id, it) })
    }

    @Transaction
    open suspend fun replaceRecordPhotos(id: String, photos: List<PhotoSlot>) {
        deleteRecordPhotos(id)
        insertRecordRows(photos.map { RecordPhotoRow(id, it) })
    }

    @Insert
    protected abstract suspend fun insertDraftRows(rows: List<DraftPhotoRow>)

    @Insert
    protected abstract suspend fun insertRecordRows(rows: List<RecordPhotoRow>)

    @Query("DELETE FROM record_photos WHERE recordId = :id")
    protected abstract suspend fun deleteRecordPhotos(id: String)
}
