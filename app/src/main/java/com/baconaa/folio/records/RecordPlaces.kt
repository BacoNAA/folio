package com.baconaa.folio.records

import androidx.room.withTransaction
import java.util.UUID

@JvmInline
value class PlaceId(val value: String)

data class PlaceInfo(val name: String, val city: String = "", val address: String = "", val note: String = "")
data class Place(val id: PlaceId, val info: PlaceInfo, val isDraft: Boolean = false)
data class PlaceSummary(val place: Place, val recordCount: Int)

/** 地点身份与名称分开；记录只保存选择，共享资料从地点小册独立保存。 */
interface RecordPlaces {
    suspend fun listPlaces(keyword: String = ""): List<PlaceSummary>
    suspend fun openPlace(id: PlaceId): Place?
    suspend fun openDraftPlace(id: DraftId): Place?
    suspend fun createDraftPlace(id: DraftId, info: PlaceInfo): PlaceId
    suspend fun updateDraftPlace(id: DraftId, placeId: PlaceId, info: PlaceInfo)
    suspend fun selectDraftPlace(id: DraftId, placeId: PlaceId?)
    suspend fun updatePlace(id: PlaceId, info: PlaceInfo)
}

internal class LocalRecordPlaces(private val database: FolioDatabase, private val now: () -> Long) : RecordPlaces {
    private val dao = database.placeDao()
    private val drafts = database.textDao()

    override suspend fun listPlaces(keyword: String) = dao.sharedPlaces(keyword.trim()).map { PlaceSummary(it.place.asPlace(), it.recordCount) }
    override suspend fun openPlace(id: PlaceId) = dao.place(id.value)?.takeIf { it.ownerDraftId == null }?.asPlace()
    override suspend fun openDraftPlace(id: DraftId): Place? = database.withTransaction {
        requireDraft(id)
        dao.draftPlace(id.value)?.asPlace()
    }
    override suspend fun createDraftPlace(id: DraftId, info: PlaceInfo): PlaceId = database.withTransaction {
        requireDraft(id)
        val place = PlaceRow(UUID.randomUUID().toString(), info.normalized(), id.value)
        dao.insert(place)
        dao.replaceDraftPlace(id.value, place.id)
        drafts.touchDraft(id.value, now())
        PlaceId(place.id)
    }
    override suspend fun updateDraftPlace(id: DraftId, placeId: PlaceId, info: PlaceInfo) = database.withTransaction {
        requireDraft(id)
        val place = checkNotNull(dao.place(placeId.value))
        require(place.ownerDraftId == id.value) { "共享地点资料请从地点小册修改" }
        val changed = info.normalized()
        if (place.info != changed) {
            dao.update(place.copy(info = changed))
            drafts.touchDraft(id.value, now())
        }
    }
    override suspend fun selectDraftPlace(id: DraftId, placeId: PlaceId?) = database.withTransaction {
        requireDraft(id)
        if (placeId != null) {
            val place = checkNotNull(dao.place(placeId.value)) { "地点已不存在" }
            require(place.ownerDraftId == null || place.ownerDraftId == id.value) { "其他草稿的地点尚未共享" }
        }
        if (dao.draftPlace(id.value)?.id != placeId?.value) {
            dao.replaceDraftPlace(id.value, placeId?.value)
            drafts.touchDraft(id.value, now())
        }
    }
    override suspend fun updatePlace(id: PlaceId, info: PlaceInfo) = database.withTransaction {
        val place = checkNotNull(dao.place(id.value)) { "地点已不存在" }
        require(place.ownerDraftId == null) { "草稿地点请在原草稿中修改" }
        dao.update(place.copy(info = info.normalized()))
    }
    private suspend fun requireDraft(id: DraftId) {
        checkNotNull(drafts.draft(id.value)) { "草稿已不存在或在回收站中" }
    }
    private fun PlaceInfo.normalized() = copy(name = name.trim(), city = city.trim(), address = address.trim(), note = note.trim())
        .also { require(it.name.isNotEmpty()) { "请填写地点名称" } }
}

internal fun PlaceRow.asPlace() = Place(PlaceId(id), info, ownerDraftId != null)
