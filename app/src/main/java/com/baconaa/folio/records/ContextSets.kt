package com.baconaa.folio.records

import androidx.room.*
import java.util.UUID

@Entity(tableName = "context_sets")
internal data class ContextSetRow(@PrimaryKey val id: String, val name: String, val stampTag: String?)

@Entity(tableName = "context_tags", primaryKeys = ["setId", "tagId"], foreignKeys = [
    ForeignKey(entity = ContextSetRow::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)
])
internal data class ContextTag(val setId: String, val tagId: String)

@Entity(tableName = "context_feelings", primaryKeys = ["setId", "scaleId"], foreignKeys = [
    ForeignKey(entity = ContextSetRow::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)
])
internal data class ContextFeeling(val setId: String, val scaleId: String, val level: Int, val total: Int, val label: String) {
    fun choice() = FeelingChoice(ScaleId(scaleId), level, total, label)
}

internal data class ContextSet(val row: ContextSetRow, val tags: List<String>, val feelings: List<FeelingChoice>)

@Dao
internal interface ContextDao {
    @Query("SELECT * FROM context_sets ORDER BY name COLLATE NOCASE,id") suspend fun list(): List<ContextSetRow>
    @Query("SELECT * FROM context_sets WHERE id=:id") suspend fun row(id: String): ContextSetRow?
    @Query("SELECT tagId FROM context_tags WHERE setId=:id ORDER BY rowid") suspend fun tags(id: String): List<String>
    @Query("SELECT * FROM context_feelings WHERE setId=:id ORDER BY rowid") suspend fun feelings(id: String): List<ContextFeeling>
    @Upsert suspend fun save(row: ContextSetRow)
    @Insert suspend fun insertTags(rows: List<ContextTag>)
    @Insert suspend fun insertFeelings(rows: List<ContextFeeling>)
    @Query("DELETE FROM context_tags WHERE setId=:id") suspend fun clearTags(id: String)
    @Query("DELETE FROM context_feelings WHERE setId=:id") suspend fun clearFeelings(id: String)
    @Query("""SELECT r.id, COALESCE(NULLIF(r.title,''),NULLIF(substr(r.body,1,80),''),'记录') AS label
        FROM text_records r WHERE r.deletedAt IS NULL AND NOT EXISTS (
          SELECT 1 FROM context_tags t WHERE t.setId=:id AND NOT EXISTS (
            SELECT 1 FROM record_tags rt WHERE rt.recordId=r.id AND rt.tagId=t.tagId))
        AND NOT EXISTS (SELECT 1 FROM context_feelings c WHERE c.setId=:id AND NOT EXISTS (
          SELECT 1 FROM record_feelings f WHERE f.recordId=r.id AND f.scaleId=c.scaleId AND f.level=c.level
          AND f.total=c.total AND f.label=c.label)) ORDER BY r.firstRecordedAt DESC,r.id""")
    suspend fun matching(id: String): List<ExperienceChoice>
}

internal class ContextSets(private val db: FolioDatabase, private val scales: FeelingScales, private val tags: RecordTags) {
    private val dao = db.contextDao()
    suspend fun list() = db.withTransaction {
        dao.list().map { row -> ContextSet(row, dao.tags(row.id), dao.feelings(row.id).map { it.choice() }) }
    }

    private suspend fun validate(tagIds: List<String>, choices: List<FeelingChoice>) {
        require(tagIds.isNotEmpty() || choices.isNotEmpty()) { "请至少选择一项条件。" }
        require(db.tagDao().tags(tagIds).count { it.ownerDraftId == null } == tagIds.size) { "标签已失效，请编辑组合并重新选择。" }
        val definitions = scales.listFeelingScales().associateBy { it.id }
        require(choices.distinctBy { it.scaleId }.size == choices.size) { "同一项目只能选一种程度。" }
        for (choice in choices) {
            val scale = requireNotNull(definitions[choice.scaleId]) { "刻度项目已失效，请编辑组合。" }
            require(if (choice.level == 0) choice.total == 0 && choice.label.isNotBlank()
                else choice.level in 1..scale.levels.size && scale.choice(choice.level) == choice) {
                "刻度定义已改变，请编辑组合并重新选择程度。"
            }
        }
    }

    suspend fun save(id: String?, name: String, tagIds: List<String>, choices: List<FeelingChoice>, stamp: Tag?) = db.withTransaction {
        require(name.isNotBlank()) { "请填写组合名称。" }
        validate(tagIds, choices)
        require(stamp == null || tags.listTags().any { it.id == stamp.id && it.stampRevision != null }) { "所选图章已失效。" }
        if (id != null) requireNotNull(dao.row(id)) { "组合已不存在。" }
        val key = id ?: UUID.randomUUID().toString()
        dao.save(ContextSetRow(key, name.trim(), stamp?.id?.value))
        dao.clearTags(key)
        dao.clearFeelings(key)
        dao.insertTags(tagIds.map { ContextTag(key, it) })
        dao.insertFeelings(choices.map { ContextFeeling(key, it.scaleId.value, it.level, it.total, it.label) })
        key
    }

    private suspend fun checked(id: String): ContextSet {
        val row = requireNotNull(dao.row(id)) { "组合已不存在。" }
        val set = ContextSet(row, dao.tags(id), dao.feelings(id).map { it.choice() })
        validate(set.tags, set.feelings)
        return set
    }

    suspend fun ensureAvailable(id: String) { db.withTransaction { checked(id) } }
    suspend fun matches(id: String) = db.withTransaction { checked(id); dao.matching(id) }
    suspend fun apply(id: String, draft: DraftId) = db.withTransaction {
        val set = checked(id)
        val existing = requireNotNull(tags.openDraftTags(draft)) { "请重新打开草稿。" }
        tags.saveDraftTags(draft, (existing.map { it.id } + set.tags.map(::TagId)).distinct())
        for (choice in set.feelings) scales.setDraftFeeling(draft, choice)
    }
}

// 已改变的刻度或失效标签可以保留供用户修正，但无条件的组合不能参与查询。
internal suspend fun validateContextSets(dao: ContextDao) {
    for (row in dao.list()) {
        require(row.name.isNotBlank()) { "情境组合缺少名称。" }
        val tags = dao.tags(row.id)
        val feelings = dao.feelings(row.id)
        require((tags.isNotEmpty() || feelings.isNotEmpty()) && tags.none { it.isBlank() }) { "情境组合缺少有效条件。" }
        feelings.forEach { it.choice() }
    }
}
