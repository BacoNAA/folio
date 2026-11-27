package com.baconaa.folio.records

import androidx.room.withTransaction
import java.util.UUID

@JvmInline value class TemplateId(val value: String)

enum class TemplateFieldType(val label: String) { TEXT("文字"), PHOTO("照片"), AUDIO("录音"), CHECKLIST("清单") }

data class TemplateField(val type: TemplateFieldType, val name: String = type.label) {
    internal fun emptyItem(id: ItemId = newItemId()): ContentItem = when (type) {
        TemplateFieldType.TEXT -> ContentItem.Text(id, "", name)
        TemplateFieldType.CHECKLIST -> ContentItem.Checklist(id, emptyList(), name)
        TemplateFieldType.PHOTO, TemplateFieldType.AUDIO -> ContentItem.EmptyMediaField(id, this)
    }
}

data class RecordTemplate(val id: TemplateId, val revision: Long, val name: String,
    val fields: List<TemplateField>, val tags: List<Tag>, val scales: Set<ScaleId> = emptySet())

class TemplateChanged : IllegalStateException("模板已更新，请重新查看当前字段。")

interface RecordTemplates {
    suspend fun listTemplates(): List<RecordTemplate>
    /** 新建传 null；修改须与预览版本一致。只接受已共享标签的身份。 */
    suspend fun saveTemplate(previous: RecordTemplate?, name: String, fields: List<TemplateField>, tags: Set<TagId>,
        scales: Set<ScaleId> = previous?.scales.orEmpty()): RecordTemplate
    /** 按预览的版本起草，复制字段和标签引用；首次记录时间仍取第一次保存实际内容的时刻。 */
    suspend fun startTemplateDraft(id: TemplateId, expectedRevision: Long, eventTime: EventTime? = null): DraftId
}

internal fun ContentItem.templateField(): TemplateField? {
    val type = when (this) {
        is ContentItem.Text -> TemplateFieldType.TEXT
        is ContentItem.Photo -> TemplateFieldType.PHOTO
        is ContentItem.Audio -> TemplateFieldType.AUDIO
        is ContentItem.Checklist -> TemplateFieldType.CHECKLIST
        is ContentItem.EmptyMediaField -> return field
        else -> return null
    }
    return TemplateField(type, fieldName ?: type.label)
}

internal class LocalRecordTemplates(private val db: FolioDatabase, private val now: () -> Long) : RecordTemplates {
    private val dao = db.templateDao()

    override suspend fun listTemplates() = dao.list().map { it.asTemplate() }

    override suspend fun saveTemplate(previous: RecordTemplate?, name: String, fields: List<TemplateField>, tags: Set<TagId>, scales: Set<ScaleId>) =
        db.withTransaction {
            val title = name.trim()
            require(title.isNotEmpty() && fields.isNotEmpty() && fields.none { it.name.isBlank() }) { "请填写模板名称和每个字段的名称。" }
            val selected = db.tagDao().tags(tags.map { it.value })
            require(selected.size == tags.size && selected.all { it.ownerDraftId == null }) { "请选择已共享的标签。" }
            val id = previous?.id?.value ?: UUID.randomUUID().toString()
            val row = TemplateRow(id, title, (previous?.revision ?: 0) + 1)
            if (previous == null) dao.insert(row)
            else if (dao.update(id, previous.revision, title) != 1) throw TemplateChanged()
            val feelings = db.feelingDao()
            feelings.clearTemplate(id)
            feelings.attach(scales.map { TemplateFeelingRow(id, it.value) })
            dao.clearFields(id)
            dao.clearTags(id)
            dao.addFields(fields.mapIndexed { position, field -> TemplateFieldRow(id, position, field.name.trim(), field.type) })
            dao.addTags(tags.map { TemplateTagRow(id, it.value) })
            checkNotNull(dao.get(id)).asTemplate()
        }

    override suspend fun startTemplateDraft(id: TemplateId, expectedRevision: Long, eventTime: EventTime?): DraftId =
        db.withTransaction {
            val template = dao.get(id.value)?.asTemplate()
            if (template == null || template.revision != expectedRevision) throw TemplateChanged()
            val draft = DraftId(UUID.randomUUID().toString())
            val timestamp = now()
            val items = template.fields.map { it.emptyItem() }
            db.textDao().insertDraft(DraftRow(draft.value, "", 0, timestamp, timestamp, eventTime = eventTime?.asFields()))
            db.writeMixedDraft(draft, MixedDraft(items, items.first().id, 0), timestamp)
            template.scales.forEach { db.feelingDao().save(DraftFeelingRow(draft.value, FeelingSlot(it.value, 0, 0, "", true))) }
            db.tagDao().replaceDraftTags(draft.value, template.tags.map { it.id.value })
            draft
        }
}
