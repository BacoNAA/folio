package com.baconaa.folio.records

import androidx.room.*

@Entity(tableName = "record_templates")
internal data class TemplateRow(@PrimaryKey val id: String, val name: String, val revision: Long)

@Entity(tableName = "template_fields", primaryKeys = ["templateId", "position"],
    foreignKeys = [ForeignKey(entity = TemplateRow::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE)])
internal data class TemplateFieldRow(val templateId: String, val position: Int, val name: String, val type: TemplateFieldType)

@Entity(tableName = "template_tags", primaryKeys = ["templateId", "tagId"], indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = TemplateRow::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagRow::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ])
internal data class TemplateTagRow(val templateId: String, val tagId: String)

internal data class TemplateDefinition(
    @Embedded val template: TemplateRow,
    @Relation(parentColumn = "id", entityColumn = "templateId", entity = TemplateFeelingRow::class, projection = ["scaleId"])
    val scales: List<String>,
    @Relation(parentColumn = "id", entityColumn = "templateId") val fields: List<TemplateFieldRow>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(TemplateTagRow::class,
        parentColumn = "templateId", entityColumn = "tagId")) val tags: List<TagDisplayRow>,
) {
    fun asTemplate() = RecordTemplate(TemplateId(template.id), template.revision, template.name,
        fields.sortedBy { it.position }.map { TemplateField(it.type, it.name) },
        tags.sortedWith(compareBy({ it.name }, { it.id })).map { it.asTag() }, scales.map(::ScaleId).toSet())
}

@Dao
internal interface TemplateDao {
    @Transaction @Query("SELECT * FROM record_templates ORDER BY name, id") suspend fun list(): List<TemplateDefinition>
    @Transaction @Query("SELECT * FROM record_templates WHERE id = :id") suspend fun get(id: String): TemplateDefinition?
    @Insert suspend fun insert(row: TemplateRow)
    @Query("UPDATE record_templates SET name = :name, revision = revision + 1 WHERE id = :id AND revision = :revision")
    suspend fun update(id: String, revision: Long, name: String): Int
    @Query("DELETE FROM template_fields WHERE templateId = :id") suspend fun clearFields(id: String)
    @Query("DELETE FROM template_tags WHERE templateId = :id") suspend fun clearTags(id: String)
    @Insert suspend fun addFields(rows: List<TemplateFieldRow>)
    @Insert suspend fun addTags(rows: List<TemplateTagRow>)
}
