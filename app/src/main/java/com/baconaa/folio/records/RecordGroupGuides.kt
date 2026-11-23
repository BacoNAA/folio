package com.baconaa.folio.records

import androidx.room.withTransaction

@JvmInline
value class GuideSectionId(val value: String) {
    init { require(value.isNotBlank()) { "分段标识不能为空" } }
}

data class GuideSection(val id: GuideSectionId, val title: String = "", val description: String = "")
data class GuideMember(val recordId: RecordId, val sectionId: GuideSectionId? = null)

/** sections 是导览的段序，members 沿用普通组的可见成员顺序。未分段成员在导览末尾阅读。 */
data class GuideLayout(val description: String, val sections: List<GuideSection>, val members: List<GuideMember>)
data class GroupGuideContent(val content: RecordGroupContent, val layout: GuideLayout)

sealed interface GuideUpdate {
    data object Saved : GuideUpdate
    data class Changed(val current: GroupGuideContent) : GuideUpdate
    data object MissingGroup : GuideUpdate
}

interface RecordGroupGuides {
    suspend fun openGuide(id: RecordGroupId): GroupGuideContent?
    /** expected 为进入编辑时所见的安排。发生冲突返回当前安排，读写故障抛出异常以便保留输入。 */
    suspend fun saveGuide(id: RecordGroupId, expected: GuideLayout, edited: GuideLayout): GuideUpdate
}

internal class LocalRecordGroupGuides(private val database: FolioDatabase) : RecordGroupGuides {
    private val groups = database.groupDao()
    private val guides = database.guideDao()
    private val groupReader = LocalRecordGroups(database)

    override suspend fun openGuide(id: RecordGroupId): GroupGuideContent? = database.withTransaction {
        val row = groups.group(id.value) ?: return@withTransaction null
        val content = groupReader.readContent(row) ?: return@withTransaction null
        GroupGuideContent(content, layout(row))
    }

    private suspend fun layout(group: GroupRow) = GuideLayout(group.guideDescription,
        guides.sections(group.id).map { GuideSection(GuideSectionId(it.id), it.title, it.description) },
        guides.members(group.id).map { GuideMember(RecordId(it.recordId), it.sectionId?.let(::GuideSectionId)) })

    override suspend fun saveGuide(id: RecordGroupId, expected: GuideLayout, edited: GuideLayout): GuideUpdate = database.withTransaction {
        val row = groups.group(id.value)?.takeIf { it.name != null } ?: return@withTransaction GuideUpdate.MissingGroup
        val current = layout(row)
        if (current == edited) return@withTransaction GuideUpdate.Saved
        if (current != expected) return@withTransaction GuideUpdate.Changed(GroupGuideContent(requireNotNull(groupReader.readContent(row)), current))

        val sections = edited.sections.map { it.id }.toSet()
        require(sections.size == edited.sections.size) { "同一分段不能出现两次" }
        val records = edited.members.map { it.recordId }
        val oldRecords = current.members.map { it.recordId }
        require(records.size == oldRecords.size && records.toSet() == oldRecords.toSet()) { "导览必须保留组内全部可见成员" }
        require(edited.members.all { it.sectionId == null || it.sectionId in sections }) { "成员所选分段已不存在" }

        if (current.description != edited.description) guides.setDescription(row.id, edited.description)
        if (current.sections != edited.sections) {
            val removed = current.sections.filter { it.id !in sections }.map { it.id.value }
            removed.chunked(RECORD_ID_BATCH_SIZE).forEach { guides.deleteSections(row.id, it) }
            guides.putSections(edited.sections.mapIndexed { index, s -> GuideSectionRow(row.id, s.id.value, index, s.title, s.description) })
        }
        if (current.members != edited.members) {
            if (oldRecords != records) groups.setOrder(row.id, records.map { it.value })
            // 隐藏收录可以随回收站恢复。编辑可见成员时保留它们原有的分段。
            guides.clearVisibleAssignments(row.id)
            guides.insertAssignments(edited.members.mapNotNull { member ->
                member.sectionId?.let { GuideAssignmentRow(row.id, member.recordId.value, it.value) }
            })
        }
        GuideUpdate.Saved
    }
}
