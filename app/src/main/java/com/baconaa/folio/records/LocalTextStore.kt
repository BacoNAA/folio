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
) : TextDrafts, PublishedTextRecords {
    private val dao = database.textDao()
    private val photoDao = database.photoDao()

    override suspend fun appendPhotos(id: DraftId?, photos: List<PhotoId>): DraftId {
        require(photos.isNotEmpty()) { "请选择照片" }
        return database.withTransaction {
            requireReadableOriginals(photos)
            val timestamp = now()
            val draftId = id ?: DraftId(UUID.randomUUID().toString())
            if (id == null) {
                dao.insertDraft(DraftRow(draftId.value, "", 0, timestamp, timestamp))
            } else {
                check(dao.touchDraft(id.value, timestamp) == 1) { "草稿已不存在" }
            }
            val start = photoDao.nextDraftPosition(draftId.value)
            photoDao.insertDraftPhotos(draftId.value, photos.mapIndexed { offset, photo ->
                PhotoSlot(itemId = UUID.randomUUID().toString(), photoId = photo.value.toString(), position = start + offset)
            })
            draftId
        }
    }

    private fun requireReadableOriginals(photos: List<PhotoId>) {
        for (photo in photos.distinct()) {
            originals.openOriginal(photo).use {
                if (it.read() == -1) throw IOException("原图为空，请重新导入照片")
            }
        }
    }

    override suspend fun openDraftPhotos(id: DraftId): List<PhotoId>? = database.withTransaction {
        if (dao.draft(id.value) == null) null else photoDao.draftPhotos(id.value).map { it.photoId.asPhotoId() }
    }

    private fun String.asPhotoId() = PhotoId(UUID.fromString(this))

    override suspend fun editRecord(id: RecordId): DraftId = database.withTransaction {
        dao.activeEditId(id.value)?.let { return@withTransaction DraftId(it) }
        val record = checkNotNull(dao.activeRecord(id.value)) { "正式记录已不存在" }
        val draftId = UUID.randomUUID().toString()
        dao.insertDraft(DraftRow(draftId, record.body, record.body.length, record.firstRecordedAt, now()))
        dao.insertEdit(RecordEditRow(draftId, record.id, record.revision))
        DraftId(draftId)
    }

    override suspend fun discardDraft(id: DraftId) {
        database.withTransaction {
            if (dao.deleteDraft(id.value) == 1) dao.deleteEdit(id.value)
        }
    }

    override suspend fun saveDraft(id: DraftId?, edit: TextEdit): DraftId? {
        if (id != null) {
            check(dao.updateDraft(id.value, edit.text, edit.cursor, now()) == 1) {
                "草稿已不存在"
            }
            return id
        }
        if (edit.text.isBlank()) return null

        val newId = DraftId(UUID.randomUUID().toString())
        val timestamp = now()
        dao.insertDraft(DraftRow(newId.value, edit.text, edit.cursor, timestamp, timestamp))
        return newId
    }

    override suspend fun listDrafts(): List<DraftPreview> =
        dao.listDrafts().map { row ->
            DraftPreview(DraftId(row.id), row.excerpt, row.recordId?.let(::RecordId), row.firstPhotoId?.asPhotoId())
        }

    override suspend fun openDraft(id: DraftId): TextEdit? =
        dao.draft(id.value)?.let { TextEdit(it.body, it.cursor) }

    override suspend fun completeDraft(id: DraftId, finalEdit: TextEdit): RecordId =
        database.withTransaction {
            // A retry can arrive after the first transaction committed and removed the draft.
            dao.completedRecordId(id.value)?.let { return@withTransaction RecordId(it) }
            require(finalEdit.text.isNotBlank()) { "请填写正文后再完成" }
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            check(photoDao.draftPhotos(id.value).isEmpty()) { "含照片的草稿不能按纯文字提交" }
            val edit = dao.recordEdit(id.value)
            val recordId = if (edit == null) {
                val newId = UUID.randomUUID().toString()
                dao.insertRecord(RecordRow(newId, draft.id, finalEdit.text, draft.firstRecordedAt, now()))
                newId
            } else {
                val record = checkNotNull(dao.activeRecord(edit.recordId)) { "正式记录已不存在" }
                check(record.revision == edit.baseRevision) { "正式记录已更新，本次草稿已保留" }
                if (record.body != finalEdit.text) dao.updateRecordBody(record.id, finalEdit.text, now())
                record.id
            }
            check(dao.deleteDraft(draft.id) == 1) { "未能移除已完成的草稿" }
            RecordId(recordId)
        }

    override suspend fun search(keyword: String): List<RecordPreview> =
        dao.searchRecords(keyword).map { RecordPreview(RecordId(it.id), it.excerpt) }

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
