package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Update

/** 删除后仅保留创建标识，迟到的创建重试也不能重建该组。 */
@Entity(tableName = "record_groups")
internal data class GroupRow(@PrimaryKey val id: String, val name: String?,
    @ColumnInfo(defaultValue = "''") val guideDescription: String = "")

@Entity(
    tableName = "record_group_members", primaryKeys = ["groupId", "recordId"],
    foreignKeys = [
        ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("recordId"), Index(value = ["groupId", "position"], unique = true)],
)
internal data class GroupMemberRow(
    val groupId: String, val recordId: String, val position: Long?,
    val previousRecordId: String? = null, val nextRecordId: String? = null,
)

@Dao
internal abstract class GroupDao {
    @Query("SELECT * FROM record_groups WHERE name IS NOT NULL ORDER BY rowid DESC")
    abstract suspend fun listGroups(): List<GroupRow>

    @Query("SELECT * FROM record_groups WHERE id = :id")
    abstract suspend fun group(id: String): GroupRow?

    @Insert
    abstract suspend fun insertGroup(group: GroupRow)

    @Query("UPDATE record_groups SET name = NULL, guideDescription = '' WHERE id = :id AND name IS NOT NULL")
    abstract suspend fun retireGroup(id: String): Int

    @Query("SELECT id FROM text_records WHERE id IN (:ids) AND deletedAt IS NULL")
    abstract suspend fun activeRecordIds(ids: List<String>): List<String>

    @Query("SELECT recordId FROM record_group_members WHERE groupId = :groupId AND position IS NOT NULL ORDER BY position")
    abstract suspend fun memberIds(groupId: String): List<String>

    @RewriteQueriesToDropUnusedColumns
    @Query("""SELECT r.* FROM record_group_members m JOIN text_records r ON r.id = m.recordId
              WHERE m.groupId = :groupId AND m.position IS NOT NULL AND r.deletedAt IS NULL ORDER BY m.position""")
    abstract suspend fun memberCards(groupId: String): List<BrowseRecordRow>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM record_group_members WHERE groupId = :groupId")
    abstract suspend fun nextPosition(groupId: String): Long

    // 从当前末尾分配新位置，已有的 (groupId, recordId) 保留原位。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun appendMembers(members: List<GroupMemberRow>)

    @Query("DELETE FROM record_group_members WHERE groupId = :groupId AND recordId = :recordId AND position IS NOT NULL")
    abstract suspend fun removeMember(groupId: String, recordId: String): Int

    @Query("DELETE FROM record_group_members WHERE groupId = :groupId")
    abstract suspend fun removeAllMembers(groupId: String)

    @Query("""UPDATE record_group_members SET
              previousRecordId = (SELECT m.recordId FROM record_group_members m WHERE m.groupId = record_group_members.groupId
                                  AND m.position < record_group_members.position ORDER BY m.position DESC LIMIT 1),
              nextRecordId = (SELECT m.recordId FROM record_group_members m WHERE m.groupId = record_group_members.groupId
                              AND m.position > record_group_members.position ORDER BY m.position LIMIT 1),
              position = NULL WHERE recordId = :recordId AND position IS NOT NULL""")
    abstract suspend fun hideMemberships(recordId: String)

    @Query("SELECT * FROM record_group_members WHERE recordId = :recordId AND position IS NULL")
    protected abstract suspend fun hiddenMemberships(recordId: String): List<GroupMemberRow>

    @Query("UPDATE record_group_members SET position = NULL WHERE groupId = :groupId AND position IS NOT NULL")
    protected abstract suspend fun clearPositions(groupId: String)

    @Update
    protected abstract suspend fun updateMembers(members: List<GroupMemberRow>)

    // 在调序或恢复的写事务内先释放可见位置，互换顺序不会撞到唯一索引。其他隐藏成员保留原相邻项。
    suspend fun setOrder(groupId: String, order: List<String>) {
        clearPositions(groupId)
        updateMembers(order.mapIndexed { index, recordId -> GroupMemberRow(groupId, recordId, index.toLong()) })
    }

    suspend fun restoreMemberships(recordId: String) {
        for (membership in hiddenMemberships(recordId)) {
            val order = memberIds(membership.groupId).toMutableList()
            val previous = order.indexOf(membership.previousRecordId)
            val next = order.indexOf(membership.nextRecordId)
            val position = when {
                previous >= 0 -> previous + 1
                next >= 0 -> next
                else -> order.size
            }
            order.add(position, recordId)
            setOrder(membership.groupId, order)
        }
    }
}
