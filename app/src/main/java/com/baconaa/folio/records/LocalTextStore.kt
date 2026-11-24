package com.baconaa.folio.records

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.media.ImportedOriginals
import com.baconaa.folio.media.PdfId
import com.baconaa.folio.media.PhotoId
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalTextStore internal constructor(
    private val database: FolioDatabase,
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : TextDrafts, PublishedTextRecords, MixedContentDrafts, MediaImports, RecordTrash, RecordTimeEditing, SeasonSettings, RecordMarking,
    RecordBrowsing by LocalRecordBrowsing(database), RecordGroups by LocalRecordGroups(database),
    RecordGroupGuides by LocalRecordGroupGuides(database), RecordTags by LocalRecordTags(database, now),
    RecordPlaces by LocalRecordPlaces(database, now), RecordObjects by LocalRecordObjects(database, now) {
    private val dao = database.textDao()
    private val itemDao = database.itemDao()
    private val trashDao = database.trashDao()
    private val tagDao = database.tagDao()
    private val placeDao = database.placeDao()
    private val objectDao = database.objectDao()
    private val itemActions = ContentItemActions(database, now)
    private val originals = LocalPhotoOriginals.get(context)
    private val audioOriginals = LocalAudioOriginals.get(context)
    private val importedOriginals = ImportedOriginals(context.applicationContext, audioOriginals)
    private val importQueue = database.importCleanupDao()
    private val importCleanup = ImportCleanup(database, importedOriginals, originals)
    private val cleanupQueue = database.photoCleanupDao()
    private val photoCleanup = PhotoCleanup(database, originals)
    private val audioCleanupQueue = database.audioCleanupDao()
    private val audioCleanup = AudioCleanup(database, audioOriginals)

    private suspend fun queueDraftMedia(id: String) {
        cleanupQueue.enqueueDraft(id)
        audioCleanupQueue.enqueueDraft(id)
        importQueue.enqueueDraft(id)
    }

    private suspend fun queueRecordMedia(id: String) {
        cleanupQueue.enqueueRecord(id)
        audioCleanupQueue.enqueueRecord(id)
        importQueue.enqueueRecord(id)
    }

    private suspend fun cleanAudioAfterCommit(): Int? = try {
        audioCleanup.run()
    } catch (_: SQLiteException) {
        Log.w("Folio", "内容操作已完成，录音清理暂不可用，可稍后重试")
        null
    }

    private suspend fun cleanPhotosAfterCommit(): Int? = try {
        photoCleanup.run()
    } catch (_: SQLiteException) {
        // 内容已提交，清理故障不能被报成保存或删除失败。队列随库保留供单独重试。
        Log.w("Folio", "内容操作已完成，原图清理暂不可用，可稍后重试")
        null
    }

    private suspend fun cleanImportsAfterCommit(): Int? = try {
        importCleanup.run()
    } catch (_: SQLiteException) {
        Log.w("Folio", "内容操作已完成，导入文件清理暂不可用，可稍后重试")
        null
    }

    private suspend fun cleanReleasedMedia() {
        cleanPhotosAfterCommit()
        cleanAudioAfterCommit()
        cleanImportsAfterCommit()
    }

    private suspend fun createDraft(eventTime: EventTime?, title: String? = null): DraftId {
        val id = DraftId(UUID.randomUUID().toString())
        val timestamp = now()
        dao.insertDraft(DraftRow(id.value, "", 0, timestamp, timestamp, eventTime = eventTime?.asFields(), title = title))
        return id
    }

    private suspend fun requireDraft(id: DraftId) =
        checkNotNull(database.loadMixedDraft(id)) { "草稿已不存在" }

    override suspend fun saveDraftTitle(id: DraftId?, title: String?, initialEventTime: EventTime?): DraftId? {
        val value = title?.takeUnless { it.isBlank() }
        return database.withTransaction {
            if (id == null) return@withTransaction value?.let { createDraft(initialEventTime, it) }
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            if (draft.title == value) return@withTransaction id
            if (value == null && draft.firstContentAt == null && draft.eventTime == null &&
                draft.activeItemId == null && !itemActions.canUndo(id) && tagDao.draftTags(draft.id).isEmpty() &&
                placeDao.draftPlace(draft.id) == null && objectDao.draftObjects(draft.id).isEmpty()
            ) {
                dao.deleteDraft(draft.id)
                return@withTransaction null
            }
            dao.updateDraft(draft.copy(title = value, lastEditedAt = now()))
            id
        }
    }

    private fun requireReadableOriginals(photos: List<PhotoId>) {
        for (photo in photos.distinct()) {
            originals.openOriginal(photo).use {
                if (it.read() == -1) throw IOException("原图为空，请重新导入照片")
            }
        }
    }

    override suspend fun appendPhotos(id: DraftId?, photos: List<PhotoId>, initialEventTime: EventTime?): DraftId {
        require(photos.isNotEmpty()) { "请选择照片" }
        return database.withTransaction {
            val draftId = id ?: createDraft(initialEventTime)
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

    private fun audioItem(audio: AudioId, caption: String): ContentItem.Audio {
        val clip = audioOriginals.inspect(audio)
        return ContentItem.Audio(newItemId(), clip.id, clip.durationMillis, caption)
    }

    override suspend fun insertLink(id: DraftId?, after: ItemId?, link: ContentItem.Link, initialEventTime: EventTime?): DraftId =
        database.withTransaction {
            require(id != null || (after == null && link.hasInput)) { "请先填写网址或选择截图" }
            val draftId = id ?: createDraft(initialEventTime)
            insertItems(draftId, after, listOf(link), 0)
            draftId
        }

    override suspend fun editLink(id: DraftId, link: ContentItem.Link) {
        val released = database.withTransaction { updateLink(id, link.id) { link } }
        if (released) cleanPhotosAfterCommit()
    }

    private suspend fun updateLink(id: DraftId, itemId: ItemId, change: (ContentItem.Link) -> ContentItem.Link): Boolean {
        val items = requireDraft(id).items.toMutableList()
        val index = items.indexOfItem(itemId)
        val previous = items[index] as? ContentItem.Link ?: error("该项不是网址")
        val link = change(previous)
        val changedScreenshot = previous.screenshotId != link.screenshotId
        if (changedScreenshot) {
            requireReadableOriginals(listOfNotNull(link.screenshotId))
            previous.screenshotId?.let { cleanupQueue.enqueue(it.value.toString()) }
        }
        items[index] = link
        database.writeMixedDraft(id, MixedDraft(items, link.id, 0), now())
        return changedScreenshot && previous.screenshotId != null
    }

    override suspend fun importLinkScreenshot(
        source: Uri, draftId: DraftId?, linkId: ItemId?, initialEventTime: EventTime?, initialPlaceId: PlaceId?,
    ): DraftId = withContext(Dispatchers.IO) {
        require(draftId != null || linkId == null) { "找不到截图所属的网址项" }
        importCleanup.run()
        val result = importCleanup.importing(ImportAsset.LINK_SCREENSHOT) { assetId ->
            val photo = originals.importPhoto(source, PhotoId(assetId))
            database.withTransaction {
                val id = draftId ?: createDraft(initialEventTime)
                if (draftId == null && initialPlaceId != null) selectDraftPlace(id, initialPlaceId)
                if (linkId == null) {
                    insertItems(id, null, listOf(ContentItem.Link(newItemId(), screenshotId = photo)), 0)
                } else {
                    updateLink(id, linkId) { it.copy(screenshotId = photo) }
                }
                importQueue.remove(assetId.toString())
                id
            }
        }
        cleanPhotosAfterCommit()
        result
    }

    override suspend fun importMedia(
        source: Uri, type: ImportType, draftId: DraftId?, after: ItemId?, initialEventTime: EventTime?, initialPlaceId: PlaceId?,
    ): ImportedItem = withContext(Dispatchers.IO) {
        require(draftId != null || after == null) { "新草稿没有可供插入的内容项" }
        try {
            importCleanup.run()
            importCleanup.importing(ImportAsset.entries.single { it.fileType == type }) { assetId ->
                val item = importedOriginals.copy(source, type, assetId)
                database.withTransaction {
                    val id = draftId ?: createDraft(initialEventTime)
                    if (draftId == null && initialPlaceId != null) selectDraftPlace(id, initialPlaceId)
                    insertItems(id, after, listOf(item), 0)
                    importQueue.remove(assetId.toString())
                    ImportedItem(id, item)
                }
            }
        } catch (failure: SQLiteFullException) {
            throw importFailure(source, type, "本机空间不足，请释放空间后重试", failure)
        } catch (failure: IOException) {
            val reason = when {
                generateSequence<Throwable>(failure) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } ->
                    "本机空间不足，请释放空间后重试"
                failure is MediaImportException -> requireNotNull(failure.message)
                else -> "文件读取或保存失败，请重新选择"
            }
            throw importFailure(source, type, reason, failure)
        }
    }

    private fun importFailure(source: Uri, type: ImportType, reason: String, cause: Exception): MediaImportException {
        val file = importedOriginals.displayName(source)?.let { "「$it」" }
            ?: when (type) {
                ImportType.PDF -> "所选 PDF"
                ImportType.AUDIO -> "所选录音"
                ImportType.VIDEO -> "所选视频"
            }
        return MediaImportException("未能导入$file。$reason。", cause)
    }

    override fun openPdf(id: PdfId) = importedOriginals.openPdf(id)

    override fun pdfViewIntent(item: ContentItem.Pdf) = importedOriginals.viewIntent(item)

    override fun videoSource(item: ContentItem.Video) = importedOriginals.videoSource(item)

    override fun videoThumbnail(item: ContentItem.Video) = importedOriginals.videoThumbnail(item)

    override suspend fun appendAudio(
        id: DraftId?, audio: AudioId, caption: String, initialEventTime: EventTime?,
    ): DraftId = database.withTransaction {
        val item = audioItem(audio, caption)
        val draftId = id ?: createDraft(initialEventTime)
        insertItems(draftId, null, listOf(item), 0)
        draftId
    }

    override suspend fun insertAudio(id: DraftId, after: ItemId?, audio: AudioId, caption: String): ItemId =
        database.withTransaction {
            val item = audioItem(audio, caption)
            insertItems(id, after, listOf(item), 0)
            item.id
        }

    override suspend fun editAudioCaption(id: DraftId, itemId: ItemId, caption: String) = database.withTransaction {
        val items = requireDraft(id).items.toMutableList()
        val index = items.indexOfItem(itemId)
        val audio = items[index] as? ContentItem.Audio ?: error("该项不是录音")
        items[index] = audio.copy(caption = caption)
        database.writeMixedDraft(id, MixedDraft(items, itemId, 0), now())
    }

    override suspend fun appendChecklist(
        id: DraftId?, entries: List<ChecklistEntry>, initialEventTime: EventTime?,
    ): DraftId = database.withTransaction {
        require(id != null || entries.any { it.text.isNotBlank() }) { "请填写清单事项后再保存" }
        val item = ContentItem.Checklist(newItemId(), entries.toList())
        val draftId = id ?: createDraft(initialEventTime)
        insertItems(draftId, null, listOf(item), 0)
        draftId
    }

    override suspend fun insertChecklist(id: DraftId, after: ItemId?, entries: List<ChecklistEntry>): ItemId =
        database.withTransaction {
            val item = ContentItem.Checklist(newItemId(), entries.toList())
            insertItems(id, after, listOf(item), 0)
            item.id
        }

    override suspend fun editChecklist(id: DraftId, itemId: ItemId, entries: List<ChecklistEntry>) =
        database.withTransaction {
            val items = requireDraft(id).items.toMutableList()
            val index = items.indexOfItem(itemId)
            require(items[index] is ContentItem.Checklist) { "该项不是清单" }
            items[index] = ContentItem.Checklist(itemId, entries.toList())
            database.writeMixedDraft(id, MixedDraft(items, itemId, 0), now())
        }

    private suspend fun insertItems(id: DraftId, after: ItemId?, added: List<ContentItem>, cursor: Int) {
        val draft = requireDraft(id)
        val position = after?.let { draft.items.indexOfItem(it) + 1 } ?: draft.items.size
        requireReadableOriginals(added.photos() + added.filterIsInstance<ContentItem.Link>().mapNotNull { it.screenshotId })
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

    override suspend fun moveItem(id: DraftId, itemId: ItemId, direction: MoveDirection) =
        itemActions.move(id, itemId, direction)

    override suspend fun removeItem(id: DraftId, itemId: ItemId) {
        database.withTransaction {
            requireDraft(id)
            queueDraftMedia(id.value)
            itemActions.remove(id, itemId)
        }
        cleanReleasedMedia()
    }

    override suspend fun canUndoRemoval(id: DraftId) = itemActions.canUndo(id)

    override suspend fun undoRemoval(id: DraftId) = itemActions.undo(id)

    override suspend fun editRecord(id: RecordId): DraftId = database.withTransaction {
        dao.activeEditId(id.value)?.let { return@withTransaction DraftId(it) }
        val record = checkNotNull(dao.activeRecord(id.value)) { "正式记录已不存在" }
        val draftId = DraftId(UUID.randomUUID().toString())
        val items = itemDao.recordItems(id.value)
        val focus = items.firstOrNull()
        val timestamp = now()
        dao.insertDraft(DraftRow(draftId.value, record.body, focus?.text?.length ?: 0,
            timestamp, timestamp, activeItemId = focus?.itemId, eventTime = record.eventTime,
            title = record.title, firstContentAt = record.firstRecordedAt))
        dao.insertEdit(RecordEditRow(draftId.value, record.id, record.revision))
        itemDao.replaceDraftItems(draftId.value, items)
        tagDao.replaceDraftTags(draftId.value, tagDao.recordTags(record.id).map { it.id })
        objectDao.replaceDraftObjects(draftId.value, objectDao.recordObjects(record.id).map { it.row.id })
        placeDao.replaceDraftPlace(draftId.value, placeDao.recordPlace(record.id)?.id)
        draftId
    }

    override suspend fun discardDraft(id: DraftId) {
        database.withTransaction {
            if (dao.draft(id.value) != null) {
                queueDraftMedia(id.value)
                check(dao.deleteDraft(id.value) == 1) { "未能放弃草稿" }
                dao.deleteEdit(id.value)
            }
        }
        cleanReleasedMedia()
    }

    override suspend fun saveDraft(id: DraftId?, edit: TextEdit, initialEventTime: EventTime?): DraftId? {
        if (id == null && edit.text.isBlank()) return null
        return database.withTransaction {
            val draftId = id ?: createDraft(initialEventTime)
            database.writeMixedDraft(draftId, requireDraft(draftId).withLegacyText(edit), now())
            draftId
        }
    }

    private fun MixedDraft.withLegacyText(edit: TextEdit): MixedDraft {
        // 拼接正文含清单索引文字，不能把旧接口的整段回存解释为某一段文字编辑。
        if (items.any { it is ContentItem.Checklist }) {
            require(edit.text == items.body()) { "包含清单的草稿请按内容项编辑" }
            return this
        }
        val paragraphs = items.filterIsInstance<ContentItem.Text>()
        val first = paragraphs.firstOrNull()
        if (first == null) {
            if (edit.text.isEmpty()) return this
            val item = ContentItem.Text(newItemId(), edit.text)
            return copy(items = listOf(item) + items, activeItemId = item.id, cursor = edit.cursor)
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
            DraftPreview(
                DraftId(row.id), row.excerpt, row.recordId?.let(::RecordId), row.firstPhotoId?.asPhotoId(),
                row.totalAudioDurationMillis, row.title,
            )
        }

    override suspend fun openDraft(id: DraftId): TextEdit? =
        dao.draft(id.value)?.let { TextEdit(it.body, it.cursor) }

    override suspend fun openDraftTimes(id: DraftId): DraftTimes? =
        dao.draft(id.value)?.let { DraftTimes(it.firstContentAt, it.eventTime?.asEventTime()) }

    override suspend fun readSeasonMonths(): Map<NamedSeason, SeasonMonths> {
        val settings = database.seasonSettingsDao().settings().associate { it.season to it.months }
        return NamedSeason.entries.associateWith { settings[it] ?: SeasonMonths(it.firstMonth, it.lastMonth) }
    }

    override suspend fun saveSeasonMonths(changes: Map<NamedSeason, SeasonMonths>) {
        database.seasonSettingsDao().save(changes.map { (season, months) -> SeasonSettingRow(season, months) })
    }

    override suspend fun saveDraftEventTime(id: DraftId, eventTime: EventTime?) {
        database.withTransaction {
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            dao.updateDraft(draft.copy(eventTime = eventTime?.asFields(), lastEditedAt = now()))
        }
    }

    override suspend fun completeDraft(id: DraftId, finalEdit: TextEdit) =
        publish(id) { it.withLegacyText(finalEdit) }

    override suspend fun completeMixedDraft(id: DraftId) = publish(id) { it }

    override suspend fun completeMixedDraft(id: DraftId, itemId: ItemId, finalEdit: TextEdit) =
        publish(id) { it.withText(itemId, finalEdit) }

    private suspend fun publish(id: DraftId, finalState: (MixedDraft) -> MixedDraft): RecordId {
        val result = database.withTransaction {
            dao.completedRecordId(id.value)?.let { return@withTransaction RecordId(it) }
            val draft = checkNotNull(dao.draft(id.value)) { "草稿已不存在" }
            val items = finalState(requireDraft(id)).items
            val body = items.body()
            val links = items.filterIsInstance<ContentItem.Link>()
            val audios = items.filterIsInstance<ContentItem.Audio>()
            links.firstOrNull { webAddress(it.address) == null }?.let { throw InvalidLinkAddress(it.id, it.address) }
            require(items.hasActualContent(body)) { "请填写文字、清单事项或添加媒体后再完成" }
            requireReadableOriginals(items.photos() + links.mapNotNull { it.screenshotId })
            items.filterIsInstance<ContentItem.Pdf>().forEach(importedOriginals::verify)
            items.filterIsInstance<ContentItem.Video>().forEach(importedOriginals::videoSource)
            for (audio in audios) {
                if (audioOriginals.inspect(audio.audioId).durationMillis != audio.durationMillis) {
                    throw IOException("录音原件已改变，请重新添加")
                }
            }
            queueDraftMedia(id.value)
            val slots = items.map { it.asSlot() }
            val tags = tagDao.draftTags(id.value).map { it.id }
            val objects = objectDao.draftObjects(id.value).map { it.row.id }
            val place = placeDao.draftPlace(id.value)?.id
            val edit = dao.recordEdit(id.value)
            val recordId = if (edit == null) {
                val newId = UUID.randomUUID().toString()
                val completedAt = now()
                dao.insertRecord(RecordRow(newId, draft.id, body, draft.firstContentAt ?: completedAt, completedAt,
                    eventTime = draft.eventTime, title = draft.title))
                itemDao.replaceRecordItems(newId, slots)
                newId
            } else {
                val record = checkNotNull(dao.activeRecord(edit.recordId)) { "正式记录已不存在" }
                check(record.revision == edit.baseRevision) { "正式记录已更新，本次草稿已保留" }
                val itemsChanged = slots != itemDao.recordItems(record.id)
                if (itemsChanged) {
                    queueRecordMedia(record.id)
                    itemDao.replaceRecordItems(record.id, slots)
                }
                if (itemsChanged || draft.title != record.title || draft.eventTime != record.eventTime ||
                    tags != tagDao.recordTags(record.id).map { it.id } || place != placeDao.recordPlace(record.id)?.id ||
                    objects != objectDao.recordObjects(record.id).map { it.row.id }
                ) {
                    dao.updateRecord(record.copy(body = body, modifiedAt = now(), revision = record.revision + 1,
                        eventTime = draft.eventTime, title = draft.title))
                }
                record.id
            }
            // 先转为共享再删除草稿，未选择的私有标签随草稿级联丢弃；与正式内容同事务。
            tagDao.promoteSelected(draft.id)
            tagDao.replaceRecordTags(recordId, tags)
            objectDao.promoteSelected(draft.id)
            objectDao.replaceRecordObjects(recordId, objects)
            placeDao.promoteSelected(draft.id)
            placeDao.replaceRecordPlace(recordId, place)
            check(dao.deleteDraft(draft.id) == 1) { "未能移除已完成的草稿" }
            RecordId(recordId)
        }
        cleanReleasedMedia()
        return result
    }

    override suspend fun search(keyword: String): List<RecordPreview> =
        dao.searchRecords(keyword).map { RecordPreview(RecordId(it.id), it.excerpt, it.firstPhotoId?.asPhotoId(), it.marks, it.title) }

    override suspend fun setStarred(id: RecordId, starred: Boolean) {
        check(dao.setStarred(id.value, starred) == 1) { "正式记录已不存在或在回收站中，请恢复后再修改标记" }
    }

    override suspend fun setPinned(id: RecordId, pinned: Boolean) {
        check(dao.setPinned(id.value, pinned) == 1) { "正式记录已不存在或在回收站中，请恢复后再修改标记" }
    }

    private fun String.asPhotoId() = PhotoId(UUID.fromString(this))

    override suspend fun openRecordContent(id: RecordId): RecordContent? = database.withTransaction {
        val record = dao.activeRecord(id.value) ?: return@withTransaction null
        RecordContent(itemDao.recordItems(id.value).map { it.asItem() }, record.times(), record.marks,
            tagDao.recordTags(id.value).map { it.asTag() }, placeDao.recordPlace(id.value)?.asPlace(),
            objectDao.recordObjects(id.value).map { it.asObject() }, record.title)
    }

    override suspend fun openRecord(id: RecordId): String? = dao.recordBody(id.value)

    override suspend fun moveToTrash(id: RecordId): Boolean = database.withTransaction {
        if (trashDao.moveToTrash(id.value, now()) == 0) return@withTransaction false
        database.groupDao().hideMemberships(id.value)
        true
    }

    override suspend fun restoreRecord(id: RecordId): Boolean = database.withTransaction {
        if (trashDao.restore(id.value) == 0) return@withTransaction false
        database.groupDao().restoreMemberships(id.value)
        true
    }

    override suspend fun listTrash(): List<TrashPreview> = trashDao.listTrash().map {
        TrashPreview(RecordId(it.id), it.excerpt, it.firstPhotoId?.asPhotoId(), it.deletedAt, it.hasDraft, it.marks, it.title)
    }

    override suspend fun openTrashRecord(id: RecordId): TrashedRecord? = database.withTransaction {
        val record = trashDao.trashedRecord(id.value) ?: return@withTransaction null
        TrashedRecord(
            id, RecordContent(itemDao.recordItems(id.value).map { it.asItem() }, record.times(), record.marks,
                tagDao.recordTags(id.value).map { it.asTag() }, placeDao.recordPlace(id.value)?.asPlace(),
                objectDao.recordObjects(id.value).map { it.asObject() }, record.title),
            checkNotNull(record.deletedAt), trashDao.hasDraft(id.value),
        )
    }

    override suspend fun deletePermanently(id: RecordId): PermanentDeletionResult {
        val deleted = database.withTransaction {
            if (trashDao.trashedRecord(id.value) == null) {
                check(dao.activeRecord(id.value) == null) { "请先将记录移入回收站" }
                return@withTransaction false
            }
            queueRecordMedia(id.value)
            trashDao.editingDraftIds(id.value).forEach { queueDraftMedia(it) }
            trashDao.deleteEditingDrafts(id.value)
            check(trashDao.deletePermanently(id.value) == 1) { "未能彻底删除记录" }
            true
        }
        return PermanentDeletionResult(deleted, cleanPhotosAfterCommit(), cleanAudioAfterCommit(), cleanImportsAfterCommit())
    }

    override suspend fun retryPhotoCleanup(): Int = photoCleanup.run()

    override suspend fun retryAudioCleanup(): Int = audioCleanup.run()

    override suspend fun retryImportCleanup(): Int = importCleanup.run()

    companion object {
        private var instance: LocalTextStore? = null

        @Synchronized
        fun get(context: Context): LocalTextStore = instance ?: LocalTextStore(
            Room.databaseBuilder(context.applicationContext, FolioDatabase::class.java, "folio.db").build(),
            context.applicationContext,
        ).also { instance = it }
    }
}
