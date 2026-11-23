package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "group_guide_sections", primaryKeys = ["groupId", "id"],
    foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
internal data class GuideSectionRow(val groupId: String, val id: String, val position: Int, val title: String, val description: String)

/** 成员的收录与顺序都在 G01。此表只给已有收录指定一段，任一端被删除时自动解除。 */
@Entity(tableName = "group_guide_assignments", primaryKeys = ["groupId", "recordId"],
    foreignKeys = [
        ForeignKey(entity = GroupMemberRow::class, parentColumns = ["groupId", "recordId"],
            childColumns = ["groupId", "recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GuideSectionRow::class, parentColumns = ["groupId", "id"],
            childColumns = ["groupId", "sectionId"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index(value = ["groupId", "sectionId"])])
internal data class GuideAssignmentRow(val groupId: String, val recordId: String, val sectionId: String)

internal data class GuideMemberRow(val recordId: String, val sectionId: String?)

@Dao
internal interface GroupGuideDao {
    @Query("SELECT * FROM group_guide_sections WHERE groupId = :groupId ORDER BY position")
    suspend fun sections(groupId: String): List<GuideSectionRow>

    @Query("""SELECT m.recordId, a.sectionId FROM record_group_members m
              LEFT JOIN group_guide_assignments a ON m.groupId = a.groupId AND m.recordId = a.recordId
              WHERE m.groupId = :groupId AND m.position IS NOT NULL ORDER BY m.position""")
    suspend fun members(groupId: String): List<GuideMemberRow>

    @Query("UPDATE record_groups SET guideDescription = :description WHERE id = :groupId")
    suspend fun setDescription(groupId: String, description: String)

    @Upsert
    suspend fun putSections(sections: List<GuideSectionRow>)

    @Query("DELETE FROM group_guide_sections WHERE groupId = :groupId AND id IN (:ids)")
    suspend fun deleteSections(groupId: String, ids: List<String>)

    @Query("DELETE FROM group_guide_sections WHERE groupId = :groupId")
    suspend fun deleteGuideSections(groupId: String)

    @Query("""DELETE FROM group_guide_assignments WHERE groupId = :groupId AND recordId IN
              (SELECT recordId FROM record_group_members WHERE groupId = :groupId AND position IS NOT NULL)""")
    suspend fun clearVisibleAssignments(groupId: String)

    @Insert
    suspend fun insertAssignments(rows: List<GuideAssignmentRow>)
}
