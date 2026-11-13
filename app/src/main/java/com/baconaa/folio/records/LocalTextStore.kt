package com.baconaa.folio.records

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import java.io.IOException
import java.util.UUID

class LocalTextStore internal constructor(
    private val database: FolioDatabase,
    private val originals: LocalPhotoOriginals,
    private val now: () -> Long = System::currentTimeMillis,
) : TextDrafts, PublishedTextRecords, MixedContentDrafts {
    private val dao = database.textDao()
    private val itemDao = database.itemDao()

    private suspend fun createDraft(): DraftId {
        val id = DraftId(UUID.randomUUID().toString())
        val timestamp = now()
        dao.insertDraft(DraftRow(id.value, "", 0, timestamp, timestamp))
        return id
    }

    private suspend fun requireDraft(id: DraftId) =
        checkNotNull(database.loadMixedDraft(id)) { "草稿已不存在" }

    private fun requireReadableOriginals(photos: List<PhotoId>) {
        for (photo in photos.distinct()) {
            originals.openOriginal(photo).use {
                if (it.read() == -1) throw IOException("原图为空，请重新导入照片")
            }
        }
    }

    override suspend fun appendPhotos(id: DraftId?, photos: List<PhotoId>): DraftId {
        require(photos.isNotEmpty()) { "请选择照片" }
        return database.withTransaction {
            val draftId = id ?: createDraft()
            val items = photos.map { ContentItem.Photo(newItemId(), it) }
            insertItems(draftId, null, items, 0)
            draftId
        }
    }

    override suspend fun insertText(id: DraftId, after: ItemId?, edit: TextEdit): ItemId =
        database.withTransaction {
            val item = ContentItem.Text(newItemId(), edit.text)
            insertItems(id, after, listOf(item), edit.cursor)
            item.id
        }

    override suspend fun insertPhoto(id: DraftId, after: ItemId?, photo: PhotoId, caption: String): ItemId =
        database.withTransaction {
            val item = ContentItem.Photo(newItemId(), photo, caption)
            insertItems(id, after, listOf(item), 0)
            item.id
        }

    private suspend fun insertItems(id: DraftId, after: ItemId?, added: List<ContentItem>, cursor: Int) {
        val draft = requireDraft(id)
        val position = after?.let { draft.items.indexOfItem(it) + 1 } ?: draft.items.size
        requireReadableOriginals(added.photos())
        val items = draft.items.toMutableList().apply { addAll(position, added) }
        database.writeMixedDraft(id, MixedDraft(items, added.last().id, cursor), now())
    }

    override suspend fun editTextItem(id: DraftId, itemId: ItemId, edit: TextEdit) = database.withTransaction {
        database.writeMixedDraft(id, requireDraft(id).withText(itemId, edit), now())
    }

    override suspend fun editPhotoCaption(id: DraftId, itemId: ItemId, caption: String) = database.withTransaction {
        val items = requireDraft(id).items.toMutableList()
        val index = items.indexOfItem(itemId)
        val photo = items[index] as? ContentItem.Photo ?: error("该项不是照片")
        items[index] = photo.copy(caption = caption)
        database.writeMixedDraft(id, MixedDraft(items, itemId, 0), now())
    }

    override suspend fun openMixedDraft(id: DraftId): MixedDraft? = database.withTransaction {
        database.loadMixedDraft(id)
    }

    override suspend fun openDraftPhotos(id: DraftId): List<PhotoId>? = openMixedDraft(id)?.items?.photos()

    override suspend fun editRecord(id: RecordId): DraftId = database.withTransaction {
        dao.activeEditId(id.value)?.let { return@withTransaction DraftId(it) }
        val record = checkNotNull(dao.activeRecord(id.value)) { "正式记录已不存在" }
        val draftId = DraftId(UUID.randomUUID().toString())
        val items = itemDao.recordItems(id.value)
        val focus = items.firstOrNull()
        dao.insertDraft(DraftRow(draftId.value, record.body, focus?.text?.length ?: 0,
            record.firstRecordedAt, now(), activeItemId = focus?.itemId))
        dao.insertEdit(RecordEditRow(draftId.value, record.id, record.revision))
        itemDao.replaceDraftItems(draftId.value, items)
        draftId
    }

    override suspend fun discardDraft(id: DraftId) {
        database.withTransaction {
            if (dao.deleteDraft(id.value) == 1) dao.deleteEdit(id.value)
        }
    }

    override suspend fun saveDraft(id: DraftId?, edit: TextEdit): DraftId? {
        if (id == null && edit.text.isBlank()) return null
        return database.withTransaction {
            val draftId = id ?: createDraft()
            database.writeMixedDraft(draftId, requireDraft(draftId).withLegacyText(edit), now())
            draftId
        }
    }

    private fun MixedDraft.withLegacyText(edit: TextEdit): MixedDraft {
        val paragraphs = items.filterIsInstance<ContentItem.Text>()
        val first = paragraphs.firstOrNull()
        if (first == null) {
            if (edit.text.isEmpty()) return this
            val item = ContentItem.Text(newItemId(), edit.text)
            return MixedDraft(listOf(item) + items, item.id, edit.cursor)
        }
        if (paragraphs.size == 1) return withText(first.id, edit)
        // A full-body replacement has no information about which side of a photo the edits belong to.
        require(edit.text == items.body()) { "多段图文请按内容项保存文字" }
        var remaining = edit.cursor
        for (paragraph in paragraphs) {
            if (remaining <= paragraph.text.length) return copy(activeItemId = paragraph.id, cursor = remaining)
            remaining -= paragraph.text.length + 1
        }
        error("编辑位置超出正文范围")
    }

    override suspend fun listDrafts(): List<DraftPreview> =
        dao.listDrafts().map { row ->
            DraftPreview(DraftId(row.id), row.excerpt, row.recordId?.let(::RecordId), row.firstPhotoId?.asPhotoId())
        }

    override suspend fun openDraft(id: DraftId): TextEdit? =
        dao.draft(id.value)?.let { TextEdit(it.body, it.cursor) }

    override suspend fun completeDraft(id: DraftId, finalEdit: TextEdit) =
        publish(id) { it.withLegacyText(finalEdit) }

    override suspend fun completeMixedDraft(id: DraftId) = publish(id) { it }

    override suspend fun completeMixedDraft(id: DraftId, itemId: ItemId, finalEdit: TextEdit) =
        publish(id) { it.withText(itemId, finalEdit) }

    private suspend fun publish(id: DraftId, finalState: (MixedDraft) -> MixedDraft): RecordId =
        database.withTransaction {
            dao.completedRecordId(id.value)?.let { return@withTransaction RecordId(it) }
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            val items = finalState(requireDraft(id)).items
            val body = items.body()
            val photos = items.photos()
            require(body.isNotBlank() || photos.isNotEmpty()) { "请填写正文或添加照片后再完成" }
            requireReadableOriginals(photos)
            val slots = items.map { it.asSlot() }
            val edit = dao.recordEdit(id.value)
            val recordId = if (edit == null) {
                val newId = UUID.randomUUID().toString()
                dao.insertRecord(RecordRow(newId, draft.id, body, draft.firstRecordedAt, now()))
                itemDao.replaceRecordItems(newId, slots)
                newId
            } else {
                val record = checkNotNull(dao.activeRecord(edit.recordId)) { "正式记录已不存在" }
                check(record.revision == edit.baseRevision) { "正式记录已更新，本次草稿已保留" }
                if (slots != itemDao.recordItems(record.id)) {
                    dao.updateRecordContent(record.id, body, now())
                    itemDao.replaceRecordItems(record.id, slots)
                }
                record.id
            }
            check(dao.deleteDraft(draft.id) == 1) { "未能移除已完成的草稿" }
            RecordId(recordId)
        }

    override suspend fun search(keyword: String): List<RecordPreview> =
        dao.searchRecords(keyword).map { RecordPreview(RecordId(it.id), it.excerpt, it.firstPhotoId?.asPhotoId()) }

    private fun String.asPhotoId() = PhotoId(UUID.fromString(this))

    override suspend fun openRecordContent(id: RecordId): RecordContent? = database.withTransaction {
        if (dao.recordBody(id.value) == null) return@withTransaction null
        RecordContent(itemDao.recordItems(id.value).map { it.asItem() })
    }

    override suspend fun openRecord(id: RecordId): String? = dao.recordBody(id.value)

    companion object {
        private var instance: LocalTextStore? = null

        @Synchronized
        fun get(context: Context): LocalTextStore = instance ?: LocalTextStore(
            Room.databaseBuilder(context.applicationContext, FolioDatabase::class.java, "folio.db").build(),
            LocalPhotoOriginals.get(context),
        ).also { instance = it }
    }
}
