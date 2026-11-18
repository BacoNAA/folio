package com.baconaa.folio.records

import androidx.room.*

@Entity(tableName = "places", foreignKeys = [ForeignKey(entity = DraftRow::class,
    parentColumns = ["id"], childColumns = ["ownerDraftId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ownerDraftId")])
internal data class PlaceRow(@PrimaryKey val id: String, @Embedded val info: PlaceInfo, val ownerDraftId: String?)

@Entity(tableName = "draft_places", foreignKeys = [
    ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlaceRow::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("placeId")])
internal data class DraftPlaceRow(@PrimaryKey val draftId: String, val placeId: String)

@Entity(tableName = "record_places", foreignKeys = [
    ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlaceRow::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("placeId")])
internal data class RecordPlaceRow(@PrimaryKey val recordId: String, val placeId: String)
internal data class PlaceCountRow(@Embedded val place: PlaceRow, val recordCount: Int)
internal data class RecordPlacePreviewRow(val recordId: String, @Embedded val place: PlaceRow)
internal data class PlaceTimeRow(val placeId: String, @Embedded val eventTime: EventTimeFields?)

@Dao
internal abstract class PlaceDao {
    @Query("""SELECT rp.placeId, r.eventDate, r.eventClock, r.eventZone FROM record_places rp
        JOIN text_records r ON r.id = rp.recordId WHERE r.deletedAt IS NULL""")
    abstract suspend fun activePlaceTimes(): List<PlaceTimeRow>
    @Insert abstract suspend fun insert(place: PlaceRow)
    @Update abstract suspend fun update(place: PlaceRow)
    @Query("SELECT * FROM places WHERE id = :id") abstract suspend fun place(id: String): PlaceRow?
    @Query("""SELECT p.*, (SELECT count(*) FROM record_places rp JOIN text_records r ON r.id = rp.recordId
        WHERE rp.placeId = p.id AND r.deletedAt IS NULL) AS recordCount FROM places p
        WHERE ownerDraftId IS NULL AND (instr(lower(name), lower(:keyword)) > 0 OR instr(lower(city), lower(:keyword)) > 0
        OR instr(lower(address), lower(:keyword)) > 0 OR instr(lower(note), lower(:keyword)) > 0)
        ORDER BY name COLLATE NOCASE, id""")
    abstract suspend fun sharedPlaces(keyword: String): List<PlaceCountRow>
    @Query("SELECT p.* FROM places p JOIN draft_places d ON d.placeId = p.id WHERE d.draftId = :id")
    abstract suspend fun draftPlace(id: String): PlaceRow?
    @Query("SELECT p.* FROM places p JOIN record_places r ON r.placeId = p.id WHERE r.recordId = :id")
    abstract suspend fun recordPlace(id: String): PlaceRow?
    @Query("""SELECT rp.recordId, p.* FROM record_places rp JOIN places p ON rp.placeId = p.id
        JOIN text_records r ON r.id = rp.recordId WHERE r.deletedAt IS NULL""")
    abstract suspend fun activeRecordPlaces(): List<RecordPlacePreviewRow>
    @Query("""UPDATE places SET ownerDraftId = NULL WHERE ownerDraftId = :draftId
        AND id IN (SELECT placeId FROM draft_places WHERE draftId = :draftId)""")
    abstract suspend fun promoteSelected(draftId: String)
    @Transaction open suspend fun replaceDraftPlace(id: String, placeId: String?) {
        deleteDraftPlace(id)
        if (placeId != null) insertDraftPlace(DraftPlaceRow(id, placeId))
    }
    @Transaction open suspend fun replaceRecordPlace(id: String, placeId: String?) {
        deleteRecordPlace(id)
        if (placeId != null) insertRecordPlace(RecordPlaceRow(id, placeId))
    }
    @Query("DELETE FROM draft_places WHERE draftId = :id") protected abstract suspend fun deleteDraftPlace(id: String)
    @Query("DELETE FROM record_places WHERE recordId = :id") protected abstract suspend fun deleteRecordPlace(id: String)
    @Insert protected abstract suspend fun insertDraftPlace(row: DraftPlaceRow)
    @Insert protected abstract suspend fun insertRecordPlace(row: RecordPlaceRow)
}
