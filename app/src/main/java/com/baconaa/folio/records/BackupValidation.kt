package com.baconaa.folio.records

import android.content.Context
import androidx.room.Dao
import androidx.room.Query
import com.baconaa.folio.media.ImportedOriginals
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.inspectPhoto
import com.baconaa.folio.media.inspectVideo
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Dao
internal interface BackupValidationDao {
    @Query("SELECT * FROM text_drafts WHERE id=:id")
    suspend fun draft(id: String): DraftRow

    @Query("SELECT * FROM text_records WHERE id=:id")
    suspend fun record(id: String): RecordRow
}

internal suspend fun validateBackupData(database: FolioDatabase) {
    val sql = database.openHelper.writableDatabase
    val validation = database.backupValidationDao()
    val items = database.itemDao()
    sql.query("SELECT id FROM text_drafts").use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val id = cursor.getString(0)
            validation.draft(id).eventTime?.asEventTime()
            items.draftItems(id).forEach { it.asItem() }
            database.removedItemDao().get(id)?.slot?.asItem()
        }
    }
    sql.query("SELECT id FROM text_records").use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val id = cursor.getString(0)
            validation.record(id).eventTime?.asEventTime()
            items.recordItems(id).forEach { it.asItem() }
        }
    }
    sql.query("SELECT id FROM places").use { c ->
        while (c.moveToNext()) database.placeDao().place(c.getString(0))!!.asPlace()
    }
    sql.query("SELECT id FROM record_objects").use { c ->
        while (c.moveToNext()) database.objectDao().objectById(c.getString(0))!!.asObject()
    }
    sql.query("SELECT groupId FROM life_chapters").use { chapters ->
        while (chapters.moveToNext()) {
            val id = chapters.getString(0)
            val row = requireNotNull(database.chapterDao().chapter(id))
            val name = requireNotNull(database.groupDao().group(id)?.name) { "章节缺少名称。" }
            require((row.coverRecord == null) == (row.coverItem == null)) { "章节封面引用不完整。" }
            require(row.profile(name).inputError() == null) { "章节时期或名称无效。" }
        }
    }
    sql.query("SELECT firstId,secondId FROM record_relations").use { edges ->
        while (edges.moveToNext()) require(edges.getString(0) < edges.getString(1)) { "双向记录关联无效。" }
    }
    // 素材项被移出后，仍可保留观察说明；所属小册的身份必须可解析。
    sql.query("SELECT DISTINCT ownerKind,ownerId FROM observation_nodes").use { owners ->
        while (owners.moveToNext()) {
            val kind = TrackKind.valueOf(owners.getString(0))
            val id = owners.getString(1)
            val exists = when (kind) {
                TrackKind.PLACE -> database.placeDao().place(id)?.let { it.ownerDraftId == null } == true
                else -> database.objectDao().objectById(id)?.asObject()?.let { !it.isDraft && it.kind.name == kind.name } == true
            }
            require(exists) { "观察说明缺少所属小册。" }
        }
    }
    LocalRecordTemplates(database, System::currentTimeMillis).listTemplates()
    database.seasonSettingsDao().settings()
}

internal suspend fun validateOriginal(context: Context, ref: OriginalRef, file: File) {
    require(file.length() > 0L) { "备份原件为空。" }
    val sounds = LocalAudioOriginals.get(context)
    when (ref.kind) {
        "photo" -> inspectPhoto(file)
        "audio" -> sounds.inspectDuration(file)
        "pdf" -> ImportedOriginals(context, sounds).inspectPdf(file)
        "video" -> inspectVideo(file)
        else -> error("无法检验未知媒体。")
    }
}
