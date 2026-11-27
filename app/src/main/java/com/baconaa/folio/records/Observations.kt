package com.baconaa.folio.records

import androidx.room.*

enum class TrackKind { PERSON, THING, PLACE }
data class TrackOwner(val kind: TrackKind, val id: String) {
    constructor(value: RecordObject) : this(if (value.kind == ObjectKind.PERSON) TrackKind.PERSON else TrackKind.THING, value.id.value)
    constructor(place: Place) : this(TrackKind.PLACE, place.id.value)

    fun sourceQuery() = when (kind) {
        TrackKind.PERSON -> RecordQuery(peopleIds = setOf(ObjectId(id)))
        TrackKind.THING -> RecordQuery(thingIds = setOf(ObjectId(id)))
        TrackKind.PLACE -> RecordQuery(placeId = PlaceId(id))
    }
}

data class Observation(val record: RecordId, val itemId: ItemId, val item: ContentItem?, val eventTime: EventTime?, val title: String?, val note: String)

interface RecordObservations {
    suspend fun readObservations(owner: TrackOwner): List<Observation>
    suspend fun saveObservation(owner: TrackOwner, record: RecordId, item: ItemId, note: String)
    suspend fun removeObservation(owner: TrackOwner, record: RecordId, item: ItemId)
}

@Entity(tableName = "observation_nodes", primaryKeys = ["ownerKind", "ownerId", "recordId", "itemId"],
    foreignKeys = [ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordId")])
internal data class ObservationRow(val ownerKind: String, val ownerId: String, val recordId: String, val itemId: String,
                                   val note: String, val addedAt: Long)

// 正式编辑会按新顺序重写 record_items。通过稳定的 itemId 找回素材，保留重排前的观察说明。
private const val VISIBLE_SOURCE = """ FROM text_records r WHERE r.id=:record AND r.deletedAt IS NULL AND (
        (:kind='PLACE' AND EXISTS(SELECT 1 FROM record_places WHERE recordId=r.id AND placeId=:owner)) OR
        EXISTS(SELECT 1 FROM published_objects p JOIN record_objects o ON p.objectId=o.id
               WHERE p.recordId=r.id AND p.objectId=:owner AND o.kind=:kind))"""

@Dao
internal interface ObservationDao {
    @Query("SELECT r.* " + VISIBLE_SOURCE)
    suspend fun source(kind: String, owner: String, record: String): RecordRow?
    @Query("SELECT * FROM observation_nodes WHERE ownerKind=:kind AND ownerId=:owner ORDER BY addedAt, rowid")
    suspend fun forOwner(kind: String, owner: String): List<ObservationRow>
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM record_items WHERE recordId=:record AND itemId=:item")
    suspend fun slot(record: String, item: String): ContentSlot?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: ObservationRow): Long
    @Query("UPDATE observation_nodes SET note=:note WHERE ownerKind=:kind AND ownerId=:owner AND recordId=:record AND itemId=:item")
    suspend fun changeNote(kind: String, owner: String, record: String, item: String, note: String)
    @Query("DELETE FROM observation_nodes WHERE ownerKind=:kind AND ownerId=:owner AND recordId=:record AND itemId=:item")
    suspend fun remove(kind: String, owner: String, record: String, item: String)
}

internal class LocalObservations(private val database: FolioDatabase, private val now: () -> Long) : RecordObservations {
    private val dao = database.observationDao()
    override suspend fun readObservations(owner: TrackOwner) = database.withTransaction {
        dao.forOwner(owner.kind.name, owner.id).mapNotNull { node ->
            val record = dao.source(node.ownerKind, node.ownerId, node.recordId) ?: return@mapNotNull null
            val item = dao.slot(node.recordId, node.itemId)?.asItem()?.takeIf { it.hasContent() }
            Observation(RecordId(node.recordId), ItemId(node.itemId), item, record.times().eventTime, record.title, node.note)
        }.sortedBy { it.eventTime?.dateRange?.start ?: java.time.LocalDate.MAX }
    }

    override suspend fun saveObservation(owner: TrackOwner, record: RecordId, item: ItemId, note: String) = database.withTransaction {
        require(dao.source(owner.kind.name, owner.id, record.value) != null) { "来源素材不在当前小册，请重新选择。" }
        val slot = requireNotNull(dao.slot(record.value, item.value)) { "来源素材已移除，请重新选择。" }
        require(slot.asItem().hasContent()) { "请选择有实际内容的素材。" }
        val node = ObservationRow(owner.kind.name, owner.id, record.value, item.value, note.trim(), now())
        if (dao.insert(node) == -1L) dao.changeNote(node.ownerKind, node.ownerId, node.recordId, node.itemId, node.note)
    }

    override suspend fun removeObservation(owner: TrackOwner, record: RecordId, item: ItemId) =
        dao.remove(owner.kind.name, owner.id, record.value, item.value)
}
