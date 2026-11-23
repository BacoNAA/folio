package com.baconaa.folio.records

import androidx.room.withTransaction

@JvmInline
value class RecordGroupId(val value: String) {
    init { require(value.isNotBlank()) { "记录组标识不能为空" } }
}

data class RecordGroup(val id: RecordGroupId, val name: String)
data class RecordGroupContent(val group: RecordGroup, val members: List<RecordCard>)

sealed interface GroupUpdate {
    data class Saved(val id: RecordGroupId, val order: List<RecordId>) : GroupUpdate
    data class UnavailableRecords(val ids: List<RecordId>) : GroupUpdate
    data class OrderChanged(val current: List<RecordId>) : GroupUpdate
    data object MissingGroup : GroupUpdate
}

/** 组内只收录正式记录的引用。存储失败抛出异常，业务冲突带回可用于重试的结果。 */
interface RecordGroups {
    suspend fun listGroups(): List<RecordGroup>
    /** 已删除或不存在时返回 null。仍存在的空组返回空成员列表。 */
    suspend fun openGroup(id: RecordGroupId): RecordGroupContent?

    /** 每次新建由调用方生成一个 id。如果结果未知，沿用同一个 id 重试。 */
    suspend fun createGroup(id: RecordGroupId, name: String, members: List<RecordId>): GroupUpdate
    suspend fun appendToGroup(id: RecordGroupId, members: List<RecordId>): GroupUpdate
    suspend fun reorderGroup(id: RecordGroupId, expected: List<RecordId>, order: List<RecordId>): GroupUpdate
    suspend fun removeFromGroup(id: RecordGroupId, member: RecordId): Boolean
    suspend fun deleteGroup(id: RecordGroupId): Boolean
}

internal class LocalRecordGroups(private val database: FolioDatabase) : RecordGroups {
    private val dao = database.groupDao()
    private val browsing = LocalRecordBrowsing(database)

    override suspend fun listGroups(): List<RecordGroup> = dao.listGroups().map { row ->
        RecordGroup(RecordGroupId(row.id), checkNotNull(row.name))
    }

    override suspend fun openGroup(id: RecordGroupId): RecordGroupContent? = database.withTransaction {
        dao.group(id.value)?.let { readContent(it) }
    }

    internal suspend fun readContent(row: GroupRow): RecordGroupContent? {
        val name = row.name ?: return null
        return RecordGroupContent(RecordGroup(RecordGroupId(row.id), name), browsing.readCards(dao.memberCards(row.id)))
    }

    override suspend fun createGroup(id: RecordGroupId, name: String, members: List<RecordId>): GroupUpdate =
        database.withTransaction {
            val existing = dao.group(id.value)
            if (existing != null) {
                return@withTransaction if (existing.name == null) GroupUpdate.MissingGroup else saved(id)
            }
            val groupName = name.trim()
            require(groupName.isNotEmpty()) { "请填写组名" }
            val selected = members.distinct()
            unavailable(selected)?.let { return@withTransaction it }
            dao.insertGroup(GroupRow(id.value, groupName))
            append(id, selected)
        }

    override suspend fun appendToGroup(id: RecordGroupId, members: List<RecordId>): GroupUpdate = database.withTransaction {
        if (dao.group(id.value)?.name == null) return@withTransaction GroupUpdate.MissingGroup
        val selected = members.distinct()
        unavailable(selected) ?: append(id, selected)
    }

    private suspend fun unavailable(selected: List<RecordId>): GroupUpdate.UnavailableRecords? {
        require(selected.isNotEmpty()) { "请至少选择一条正式记录" }
        val active = selected.chunked(RECORD_ID_BATCH_SIZE).flatMap { batch -> dao.activeRecordIds(batch.map { it.value }) }.toHashSet()
        val missing = selected.filter { it.value !in active }
        return if (missing.isEmpty()) null else GroupUpdate.UnavailableRecords(missing)
    }

    private suspend fun append(id: RecordGroupId, selected: List<RecordId>): GroupUpdate.Saved {
        val start = dao.nextPosition(id.value)
        dao.appendMembers(selected.mapIndexed { index, record -> GroupMemberRow(id.value, record.value, start + index) })
        return saved(id)
    }

    private suspend fun saved(id: RecordGroupId) = GroupUpdate.Saved(id, dao.memberIds(id.value).map(::RecordId))

    override suspend fun reorderGroup(id: RecordGroupId, expected: List<RecordId>, order: List<RecordId>): GroupUpdate =
        database.withTransaction {
            if (dao.group(id.value)?.name == null) return@withTransaction GroupUpdate.MissingGroup
            val current = saved(id)
            if (current.order != expected) return@withTransaction GroupUpdate.OrderChanged(current.order)
            if (current.order == order) return@withTransaction current
            require(order.size == expected.size && order.toSet() == expected.toSet()) { "调序必须保留全部可见成员" }
            dao.setOrder(id.value, order.map { it.value })
            GroupUpdate.Saved(id, order.toList())
        }

    override suspend fun removeFromGroup(id: RecordGroupId, member: RecordId): Boolean =
        dao.removeMember(id.value, member.value) == 1

    override suspend fun deleteGroup(id: RecordGroupId): Boolean = database.withTransaction {
        if (dao.retireGroup(id.value) == 0) return@withTransaction false
        database.guideDao().deleteGuideSections(id.value)
        dao.removeAllMembers(id.value)
        true
    }
}
