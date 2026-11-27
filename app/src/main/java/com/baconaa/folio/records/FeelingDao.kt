package com.baconaa.folio.records

import androidx.room.*

@Entity(tableName = "feeling_scales")
internal data class FeelingScaleRow(@PrimaryKey val id: String, val name: String, val levels: String) {
    fun asScale() = FeelingScale(ScaleId(id), name, levels.lines())
}

internal data class FeelingSlot(val scaleId: String, val level: Int, val total: Int, val label: String, val suggested: Boolean) {
    fun choice() = if (label.isEmpty() && suggested && level == 0 && total == 0) null
        else FeelingChoice(ScaleId(scaleId), level, total, label)
}

@Entity(tableName = "draft_feelings", primaryKeys = ["draftId", "scaleId"], indices = [Index("scaleId")],
    foreignKeys = [ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeelingScaleRow::class, parentColumns = ["id"], childColumns = ["scaleId"])])
internal data class DraftFeelingRow(val draftId: String, @Embedded val value: FeelingSlot)

@Entity(tableName = "record_feelings", primaryKeys = ["recordId", "scaleId"], indices = [Index("scaleId")],
    foreignKeys = [ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeelingScaleRow::class, parentColumns = ["id"], childColumns = ["scaleId"])])
internal data class RecordFeelingRow(val recordId: String, @Embedded val value: FeelingSlot)

@Entity(tableName = "place_feelings", primaryKeys = ["placeId", "scaleId"], indices = [Index("scaleId")],
    foreignKeys = [ForeignKey(entity = PlaceRow::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeelingScaleRow::class, parentColumns = ["id"], childColumns = ["scaleId"])])
internal data class PlaceFeelingRow(val placeId: String, @Embedded val value: FeelingSlot)

@Entity(tableName = "template_feeling_scales", primaryKeys = ["templateId", "scaleId"], indices = [Index("scaleId")],
    foreignKeys = [ForeignKey(entity = TemplateRow::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeelingScaleRow::class, parentColumns = ["id"], childColumns = ["scaleId"])])
internal data class TemplateFeelingRow(val templateId: String, val scaleId: String)

@Entity(tableName = "place_impressions", foreignKeys = [ForeignKey(entity = PlaceRow::class, parentColumns = ["id"],
    childColumns = ["placeId"], onDelete = ForeignKey.CASCADE)])
internal data class PlaceImpression(@PrimaryKey val placeId: String, val note: String)

internal data class NamedFeelingRow(val name: String, val scaleId: String, val level: Int, val total: Int, val label: String) {
    fun asFeeling() = NamedFeeling(name, FeelingChoice(ScaleId(scaleId), level, total, label))
}

@Dao
internal interface FeelingDao {
    @Query("SELECT * FROM feeling_scales ORDER BY name, id") suspend fun scales(): List<FeelingScaleRow>
    @Query("SELECT * FROM feeling_scales WHERE id=:id") suspend fun scale(id: String): FeelingScaleRow?
    @Upsert suspend fun save(scale: FeelingScaleRow)

    @Query("SELECT scaleId, level, total, label, suggested FROM draft_feelings WHERE draftId=:id ORDER BY scaleId")
    suspend fun draftSlots(id: String): List<FeelingSlot>
    @Upsert suspend fun save(row: DraftFeelingRow)
    @Query("DELETE FROM draft_feelings WHERE draftId=:id AND scaleId=:scaleId")
    suspend fun clearDraftValue(id: String, scaleId: String)

    @Query("SELECT scaleId, level, total, label, suggested FROM record_feelings WHERE recordId=:id ORDER BY scaleId")
    suspend fun recordSlots(id: String): List<FeelingSlot>
    @Upsert suspend fun save(row: RecordFeelingRow)

    @Query("SELECT scaleId, level, total, label, suggested FROM place_feelings WHERE placeId=:id ORDER BY scaleId")
    suspend fun placeSlots(id: String): List<FeelingSlot>
    @Upsert suspend fun save(row: PlaceFeelingRow)
    @Query("DELETE FROM place_feelings WHERE placeId=:id AND scaleId=:scaleId")
    suspend fun clearPlaceValue(id: String, scaleId: String)
    @Query("SELECT f.name, v.scaleId, v.level, v.total, v.label FROM record_feelings v JOIN feeling_scales f ON f.id=v.scaleId WHERE recordId=:id AND v.label != '' ORDER BY f.name, v.scaleId")
    suspend fun recordValues(id: String): List<NamedFeelingRow>
    @Query("DELETE FROM record_feelings WHERE recordId=:id") suspend fun clearRecord(id: String)
    @Query("SELECT DISTINCT level, total, label FROM record_feelings JOIN text_records ON recordId=id WHERE scaleId=:scaleId AND deletedAt IS NULL AND label != '' ORDER BY total, level, label")
    suspend fun history(scaleId: String): List<FeelingDegree>
    @Query("SELECT recordId FROM record_feelings WHERE scaleId=:scaleId AND level=:level AND total=:total AND label=:label")
    suspend fun matches(scaleId: String, level: Int, total: Int, label: String): List<String>
    @Query("DELETE FROM template_feeling_scales WHERE templateId=:id") suspend fun clearTemplate(id: String)
    @Insert suspend fun attach(rows: List<TemplateFeelingRow>)
    @Query("SELECT note FROM place_impressions WHERE placeId=:id") suspend fun impression(id: String): String?
    @Upsert suspend fun save(row: PlaceImpression)
    @Query("DELETE FROM place_impressions WHERE placeId=:id") suspend fun clearImpression(id: String)
}
