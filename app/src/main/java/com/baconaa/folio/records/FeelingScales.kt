package com.baconaa.folio.records

import androidx.room.withTransaction
import java.util.UUID

@JvmInline value class ScaleId(val value: String) { init { require(value.isNotBlank()) { "刻度身份无效。" } } }

data class FeelingScale(val id: ScaleId, val name: String, val levels: List<String>) {
    init {
        require(name.isNotBlank()) { "请填写刻度名称。" }
        require(levels.isNotEmpty() && levels.none { it.isBlank() || '\n' in it || '\r' in it }) { "请逐行填写每一级的含义。" }
    }
    fun choice(level: Int) = FeelingChoice(id, level, levels.size, levels[level - 1])
}
data class FeelingChoice(val scaleId: ScaleId, val level: Int, val total: Int, val label: String) {
    init {
        require(label.isNotBlank() && ((level == 0 && total == 0) || level in 1..total)) { "刻度程度无效。" }
    }
    val display get() = if (total == 0) label else "$level/$total · $label"
    internal fun slot(suggested: Boolean) = FeelingSlot(scaleId.value, level, total, label, suggested)
}
data class FeelingDegree(val level: Int, val total: Int, val label: String)
data class NamedFeeling(val name: String, val choice: FeelingChoice)
data class DraftFeelingState(val choices: List<FeelingChoice>, val suggested: Set<ScaleId>)

interface FeelingScales {
    suspend fun listFeelingScales(): List<FeelingScale>
    suspend fun saveFeelingScale(previous: FeelingScale?, name: String, levels: List<String>): FeelingScale
    suspend fun draftFeelingState(draft: DraftId): DraftFeelingState
    suspend fun draftFeelings(draft: DraftId): List<FeelingChoice> = draftFeelingState(draft).choices
    suspend fun setDraftFeeling(draft: DraftId, choice: FeelingChoice)
    suspend fun removeDraftFeeling(draft: DraftId, scale: ScaleId)
    suspend fun historicalFeelings(scale: ScaleId): List<FeelingChoice>
    suspend fun placeFeelings(place: PlaceId): List<FeelingChoice>
    suspend fun setPlaceFeeling(place: PlaceId, choice: FeelingChoice)
    suspend fun removePlaceFeeling(place: PlaceId, scale: ScaleId)
    suspend fun placeImpression(place: PlaceId): String
    suspend fun savePlaceImpression(place: PlaceId, note: String)
}

internal class LocalFeelingScales(private val db: FolioDatabase, private val now: () -> Long) : FeelingScales {
    private val dao = db.feelingDao()
    override suspend fun listFeelingScales() = dao.scales().map { it.asScale() }
    override suspend fun saveFeelingScale(previous: FeelingScale?, name: String, levels: List<String>) = db.withTransaction {
        val scale = FeelingScale(previous?.id ?: ScaleId(UUID.randomUUID().toString()), name.trim(), levels.map { it.trim() })
        if (previous != null) check(dao.scale(previous.id.value)?.asScale() == previous) { "刻度已更新，请重新查看后修改。" }
        dao.save(FeelingScaleRow(scale.id.value, scale.name, scale.levels.joinToString("\n")))
        scale
    }
    private suspend fun checkChoice(choice: FeelingChoice) {
        val scale = checkNotNull(dao.scale(choice.scaleId.value)?.asScale()) { "所选刻度已不存在。" }
        if (choice.level > 0) require(choice.total == scale.levels.size && scale.levels.getOrNull(choice.level - 1) == choice.label) {
            "刻度等级已变更，请重新选择。"
        }
    }
    private suspend fun requireDraft(id: DraftId) = checkNotNull(db.textDao().draft(id.value)) { "草稿已不存在或在回收站。" }
    private suspend fun requirePlace(id: PlaceId) {
        require(db.placeDao().place(id.value)?.let { it.ownerDraftId == null } == true) { "请选择共享地点。" }
    }

    override suspend fun draftFeelingState(draft: DraftId) = db.withTransaction {
        requireDraft(draft)
        val slots = dao.draftSlots(draft.value)
        DraftFeelingState(slots.mapNotNull { it.choice() }, slots.filter { it.suggested }.map { ScaleId(it.scaleId) }.toSet())
    }
    override suspend fun setDraftFeeling(draft: DraftId, choice: FeelingChoice) = db.withTransaction {
        requireDraft(draft)
        checkChoice(choice)
        val suggested = dao.draftSlots(draft.value).any { it.scaleId == choice.scaleId.value && it.suggested }
        dao.save(DraftFeelingRow(draft.value, choice.slot(suggested)))
        db.textDao().touchDraft(draft.value, now())
    }
    override suspend fun removeDraftFeeling(draft: DraftId, scale: ScaleId) = db.withTransaction {
        requireDraft(draft)
        val old = dao.draftSlots(draft.value).firstOrNull { it.scaleId == scale.value }
        if (old?.suggested == true) dao.save(DraftFeelingRow(draft.value, FeelingSlot(scale.value, 0, 0, "", true)))
        else dao.clearDraftValue(draft.value, scale.value)
        db.textDao().touchDraft(draft.value, now())
    }
    override suspend fun historicalFeelings(scale: ScaleId) =
        dao.history(scale.value).map { FeelingChoice(scale, it.level, it.total, it.label) }
    override suspend fun placeFeelings(place: PlaceId) = db.withTransaction {
        requirePlace(place)
        dao.placeSlots(place.value).mapNotNull { it.choice() }
    }
    override suspend fun setPlaceFeeling(place: PlaceId, choice: FeelingChoice) = db.withTransaction {
        requirePlace(place)
        checkChoice(choice)
        dao.save(PlaceFeelingRow(place.value, choice.slot(false)))
    }
    override suspend fun removePlaceFeeling(place: PlaceId, scale: ScaleId) = db.withTransaction {
        requirePlace(place)
        dao.clearPlaceValue(place.value, scale.value)
    }
    override suspend fun placeImpression(place: PlaceId) = db.withTransaction {
        requirePlace(place)
        dao.impression(place.value).orEmpty()
    }
    override suspend fun savePlaceImpression(place: PlaceId, note: String) = db.withTransaction {
        requirePlace(place)
        if (note.isBlank()) dao.clearImpression(place.value) else dao.save(PlaceImpression(place.value, note.trim()))
    }
}
