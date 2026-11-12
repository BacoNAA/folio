package com.baconaa.folio.records

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import java.util.UUID

class LocalTextStore internal constructor(
    private val database: FolioDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : TextDrafts, PublishedTextRecords {
    private val dao = database.textDao()

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
        dao.listDrafts().map { DraftPreview(DraftId(it.id), it.excerpt, it.recordId?.let(::RecordId)) }

    override suspend fun openDraft(id: DraftId): TextEdit? =
        dao.draft(id.value)?.let { TextEdit(it.body, it.cursor) }

    override suspend fun completeDraft(id: DraftId, finalEdit: TextEdit): RecordId =
        database.withTransaction {
            // A retry can arrive after the first transaction committed and removed the draft.
            dao.completedRecordId(id.value)?.let { return@withTransaction RecordId(it) }
            require(finalEdit.text.isNotBlank()) { "请填写正文后再完成" }
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            check(dao.recordEdit(id.value) == null) { "编辑草稿不能发布为新记录" }
            val recordId = RecordId(UUID.randomUUID().toString())
            dao.insertRecord(
                RecordRow(recordId.value, draft.id, finalEdit.text, draft.firstRecordedAt, now()),
            )
            dao.deleteDraft(draft.id)
            recordId
        }

    override suspend fun search(keyword: String): List<RecordPreview> =
        dao.searchRecords(keyword).map { RecordPreview(RecordId(it.id), it.excerpt) }

    override suspend fun openRecord(id: RecordId): String? = dao.recordBody(id.value)

    companion object {
        private var instance: LocalTextStore? = null

        @Synchronized
        fun get(context: Context): LocalTextStore = instance ?: LocalTextStore(
            Room.databaseBuilder(context.applicationContext, FolioDatabase::class.java, "folio.db").build(),
        ).also { instance = it }
    }
}
