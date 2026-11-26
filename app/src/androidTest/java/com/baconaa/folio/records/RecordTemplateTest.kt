package com.baconaa.folio.records

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordTemplateTest : PhotoStoreFixture() {
    @Test fun partialRecordAndEarlierDraftKeepTheirOwnStructure() = runBlocking {
        val oldPhoto = importPhoto(Color.RED)
        val source = store.appendPhotos(null, listOf(oldPhoto.id))
        store.insertText(source, null, TextEdit("旧菜品", 0))
        val tag = store.createDraftTag(source, "探店")
        val sameName = store.createDraftTag(source, "探店")
        val original = store.completeMixedDraft(source)
        val fields = store.openRecordContent(original)!!.templateFields
        assertEquals(listOf(TemplateFieldType.PHOTO, TemplateFieldType.TEXT), fields.map { it.type })
        val menu = listOf(fields[0].copy(name = "菜品照片"), fields[1].copy(name = "口味感受"))
        val template = store.saveTemplate(null, "探店模板", menu, setOf(tag))
        val draft = store.startTemplateDraft(template.id, template.revision)
        val pending = store.startTemplateDraft(template.id, template.revision)
        reopenDatabase()
        assertEquals(template, store.listTemplates().single())
        assertEquals(listOf(tag), store.openDraftTags(draft)!!.map { it.id })
        assertNull(store.openDraftTimes(draft)!!.firstRecordedAt)
        store.saveDraftTitle(draft, "周六")
        assertTrue(runCatching { store.completeMixedDraft(draft) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.openDraftPhotos(draft)!!.isEmpty())
        val text = store.openMixedDraft(draft)!!.items[1]
        store.editTextItem(draft, text.id, TextEdit("清甜", 2))
        val record = store.completeMixedDraft(draft)
        assertEquals(listOf(ContentItem.Text(text.id, "清甜", "口味感受")), store.openRecordContent(record)!!.items)
        assertEquals(menu, store.openRecordContent(record)!!.templateFields)
        assertEquals(listOf(record), store.queryRecords(mediaTypes = setOf(MediaFilter.TEXT_ONLY)).map { it.id })
        assertEquals(listOf(original), store.queryRecords(mediaTypes = setOf(MediaFilter.PHOTO)).map { it.id })
        assertEquals(listOf(original), store.queryRecords(tagIds = setOf(sameName)).map { it.id })
        assertTrue(store.search("口味感受").isEmpty())
        val latest = store.saveTemplate(template, template.name, menu.reversed(), setOf(tag))
        assertTrue(runCatching { store.startTemplateDraft(template.id, template.revision) }.exceptionOrNull() is TemplateChanged)
        assertTrue(runCatching { store.saveTemplate(template, "旧修改", menu, emptySet()) }.exceptionOrNull() is TemplateChanged)
        store.renameTag(tag, "觅食")
        val later = store.startTemplateDraft(latest.id, latest.revision)
        suspend fun fieldNames(d: DraftId) = store.openMixedDraft(d)!!.items.map { it.fieldName }
        assertEquals(menu.map { it.name }, fieldNames(pending))
        assertEquals(menu.reversed().map { it.name }, fieldNames(later))
        assertEquals(menu.map { it.name }, fieldNames(store.editRecord(record)))
        assertEquals("觅食", store.openDraftTags(later)!!.single().name)
        assertEquals("觅食", store.listTemplates().single().tags.single().name)
        assertPhotos(store.openRecordContent(original)!!.photos, oldPhoto)
    }

    @Test fun fieldsCanBeFilledMovedRemovedAndUndoneWithoutChangingTheDefinition() = runBlocking {
        val fields = listOf(TemplateField(TemplateFieldType.CHECKLIST, "备忘"), TemplateField(TemplateFieldType.PHOTO, "门牌"),
            TemplateField(TemplateFieldType.AUDIO, "现场声音"))
        val template = store.saveTemplate(null, "走访", fields, emptySet())
        val draft = store.startTemplateDraft(template.id, template.revision)
        val (list, photo, audio) = store.openMixedDraft(draft)!!.items
        store.moveItem(draft, photo.id, MoveDirection.UP)
        store.removeItem(draft, audio.id)
        assertTrue(store.undoRemoval(draft))
        store.editChecklist(draft, list.id, listOf(ChecklistEntry(ChecklistEntryId.create(), " ")))
        val picture = importPhoto(Color.BLUE)
        assertTrue(runCatching { store.fillPhotoField(draft, audio.id, picture.id) }.exceptionOrNull() is IllegalArgumentException)
        store.fillPhotoField(draft, photo.id, picture.id)
        store.insertText(draft, photo.id, TextEdit("新加的内容", 0))
        val done = store.completeMixedDraft(draft)
        reopenDatabase()
        assertEquals(listOf("门牌", "文字", "备忘", "现场声音"), store.openRecordContent(done)!!.templateFields.map { it.name })
        assertPhotos(store.openRecordContent(done)!!.photos, picture)
        assertEquals(2, store.queryRecords().single().preview.items.size)
        assertTrue(store.queryRecords(mediaTypes = setOf(MediaFilter.AUDIO, MediaFilter.CHECKLIST)).isEmpty())
        val edit = store.editRecord(done)
        store.editChecklist(edit, list.id, listOf(ChecklistEntry(ChecklistEntryId.create(), "带伞")))
        store.completeMixedDraft(edit)
        assertEquals("备忘", store.openRecordContent(done)!!.items.last().fieldName)
        assertEquals(fields, store.listTemplates().single().fields)
        val privateDraft = store.saveDraft(null, TextEdit("私有", 0))!!
        val privateTag = store.createDraftTag(privateDraft, "私有")
        assertTrue(runCatching { store.saveTemplate(template, "不能覆盖", fields, setOf(privateTag)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(template, store.listTemplates().single())
    }
}
