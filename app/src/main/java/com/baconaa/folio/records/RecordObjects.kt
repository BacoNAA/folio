package com.baconaa.folio.records

import androidx.room.withTransaction
import java.util.UUID

@JvmInline value class ObjectId(val value: String)
enum class ObjectKind { PERSON, THING }
data class ObjectInfo(val name: String, val note: String = "", val aliases: List<String> = emptyList())
data class RecordObject(val id: ObjectId, val kind: ObjectKind, val info: ObjectInfo, val isDraft: Boolean = false)

/** 名称、别名和区别说明属于同一身份；记录只保存引用，人物与物件分别筛选。 */
interface RecordObjects {
    suspend fun listObjects(): List<RecordObject>
    suspend fun openDraftObjects(id: DraftId): List<RecordObject>?
    suspend fun createDraftObject(id: DraftId, kind: ObjectKind, info: ObjectInfo): ObjectId
    suspend fun updateDraftObject(id: DraftId, objectId: ObjectId, info: ObjectInfo)
    suspend fun saveDraftObjects(id: DraftId, objectIds: List<ObjectId>)
    suspend fun updateObject(id: ObjectId, info: ObjectInfo)
}

internal class LocalRecordObjects(private val database: FolioDatabase, private val now: () -> Long) : RecordObjects {
    private val dao = database.objectDao()
    private val drafts = database.textDao()
    override suspend fun listObjects() = dao.sharedObjects().map { it.asObject() }
    override suspend fun openDraftObjects(id: DraftId): List<RecordObject>? = database.withTransaction {
        if (drafts.draft(id.value) == null) null else dao.draftObjects(id.value).map { it.asObject() }
    }
    override suspend fun createDraftObject(id: DraftId, kind: ObjectKind, info: ObjectInfo): ObjectId = database.withTransaction {
        requireDraft(id)
        val value = info.normalized()
        val row = ObjectRow(UUID.randomUUID().toString(), kind.name, value.name, value.note, id.value)
        dao.insert(row)
        dao.replaceAliases(row.id, value.aliases)
        dao.replaceDraftObjects(id.value, dao.draftObjects(id.value).map { it.row.id } + row.id)
        drafts.touchDraft(id.value, now())
        ObjectId(row.id)
    }
    override suspend fun updateDraftObject(id: DraftId, objectId: ObjectId, info: ObjectInfo) = database.withTransaction {
        requireDraft(id)
        val current = checkNotNull(dao.objectById(objectId.value)) { "对象已不存在" }
        require(current.row.ownerDraftId == id.value) { "只能修改本草稿新建的对象" }
        if (update(current, info)) drafts.touchDraft(id.value, now())
    }
    override suspend fun saveDraftObjects(id: DraftId, objectIds: List<ObjectId>) = database.withTransaction {
        requireDraft(id)
        val ids = objectIds.map { it.value }
        require(ids.distinct().size == ids.size) { "同一个对象不能重复选择" }
        val objects = dao.objects(ids)
        require(objects.size == ids.size && objects.all { it.ownerDraftId == null || it.ownerDraftId == id.value }) {
            "对象已不存在或尚未在其他草稿中完成，请重新选择"
        }
        if (ids != dao.draftObjects(id.value).map { it.row.id }) {
            dao.replaceDraftObjects(id.value, ids)
            drafts.touchDraft(id.value, now())
        }
    }
    override suspend fun updateObject(id: ObjectId, info: ObjectInfo) = database.withTransaction {
        val current = checkNotNull(dao.objectById(id.value)) { "对象已不存在" }
        require(current.row.ownerDraftId == null) { "新对象请在所属草稿中修改" }
        update(current, info)
        Unit
    }
    private suspend fun update(current: ObjectDetails, info: ObjectInfo): Boolean {
        val value = info.normalized()
        if (current.asObject().info == value) return false
        dao.update(current.row.copy(name = value.name, note = value.note))
        dao.replaceAliases(current.row.id, value.aliases)
        return true
    }
    private suspend fun requireDraft(id: DraftId) { checkNotNull(drafts.draft(id.value)) { "草稿已不存在或在回收站中" } }
}

internal fun ObjectInfo.normalized(): ObjectInfo {
    val title = name.trim().also { require(it.isNotEmpty()) { "请填写名称" } }
    return copy(name = title, note = note.trim(), aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() && it != title }.distinct())
}
internal fun ObjectDetails.asObject() = RecordObject(ObjectId(row.id), ObjectKind.valueOf(row.kind),
    ObjectInfo(row.name, row.note, aliases.sortedBy { it.position }.map { it.name }), row.ownerDraftId != null)
