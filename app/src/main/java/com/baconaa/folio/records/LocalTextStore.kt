package com.baconaa.folio.records

import android.content.Context
import androidx.room.Room
import java.util.UUID

class LocalTextStore internal constructor(
    private val database: FolioDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.textDao()

    suspend fun saveDraft(id: DraftId?, edit: TextEdit): DraftId? {
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

    suspend fun listDrafts(): List<DraftPreview> =
        dao.listDrafts().map { DraftPreview(DraftId(it.id), it.excerpt) }

    suspend fun openDraft(id: DraftId): TextEdit? =
        dao.draft(id.value)?.let { TextEdit(it.body, it.cursor) }

    companion object {
        private var instance: LocalTextStore? = null

        @Synchronized
        fun get(context: Context): LocalTextStore = instance ?: LocalTextStore(
            Room.databaseBuilder(context.applicationContext, FolioDatabase::class.java, "folio.db").build(),
        ).also { instance = it }
    }
}
