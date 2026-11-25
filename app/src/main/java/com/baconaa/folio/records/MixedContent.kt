package com.baconaa.folio.records

import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.media.PdfId
import com.baconaa.folio.media.VideoId
import com.baconaa.folio.media.AudioId
import java.util.UUID

@JvmInline
value class ItemId(val value: String)

sealed interface ContentItem {
    val id: ItemId

    data class Text(override val id: ItemId, val text: String) : ContentItem
    data class Photo(
        override val id: ItemId, val photoId: PhotoId, val caption: String = "", val strokes: List<PhotoStroke> = emptyList(),
    ) : ContentItem
    data class Link(override val id: ItemId, val address: String = "", val title: String = "",
        val note: String = "", val screenshotId: PhotoId? = null) : ContentItem {
        val hasInput get() = address.isNotBlank() || title.isNotBlank() || note.isNotBlank() || screenshotId != null
    }
    data class Pdf(
        override val id: ItemId, val pdfId: PdfId, val displayName: String, val sizeBytes: Long,
    ) : ContentItem
    data class Video(
        override val id: ItemId, val videoId: VideoId, val displayName: String, val sizeBytes: Long,
        val durationMillis: Long?,
    ) : ContentItem
    data class Audio(
        override val id: ItemId, val audioId: AudioId, val durationMillis: Long, val caption: String = "",
    ) : ContentItem {
        init { require(durationMillis > 0) { "录音时长必须大于零" } }
    }
    data class Checklist(override val id: ItemId, val entries: List<ChecklistEntry>) : ContentItem {
        init { entries.requireUniqueEntryIds() }
    }
}

/** cursor 是当前文字项内的 UTF-16 偏移。其他项或空草稿使用 0。 */
data class MixedDraft(val items: List<ContentItem>, val activeItemId: ItemId?, val cursor: Int, val title: String? = null)

interface MixedContentDrafts {
    suspend fun openMixedDraft(id: DraftId): MixedDraft?

    /** after 为 null 时追加到末尾，有值时紧接指定项插入。 */
    suspend fun insertText(id: DraftId, after: ItemId?, edit: TextEdit): ItemId
    suspend fun insertPhoto(id: DraftId, after: ItemId?, photo: PhotoId, caption: String = ""): ItemId
    /** 允许保存未写完的网址；新建草稿时至少有一个已填写字段或截图。 */
    suspend fun insertLink(id: DraftId?, after: ItemId?, link: ContentItem.Link, initialEventTime: EventTime? = null): DraftId
    suspend fun editLink(id: DraftId, link: ContentItem.Link)
    /** 只接受已成功保存的录音；时长从原件核验，id 为 null 时开始纯录音草稿。 */
    suspend fun appendAudio(
        id: DraftId?, audio: AudioId, caption: String = "", initialEventTime: EventTime? = null,
    ): DraftId
    suspend fun insertAudio(id: DraftId, after: ItemId?, audio: AudioId, caption: String = ""): ItemId
    suspend fun editAudioCaption(id: DraftId, itemId: ItemId, caption: String)
    /** 新建必须至少有一条非空白事项；已有草稿可插入空清单继续编辑。 */
    suspend fun appendChecklist(id: DraftId?, entries: List<ChecklistEntry>, initialEventTime: EventTime? = null): DraftId
    suspend fun insertChecklist(id: DraftId, after: ItemId?, entries: List<ChecklistEntry>): ItemId
    /** 保留未删事项的 ID；可增删、排序、改文字和勾选，空列表也可保存为草稿。 */
    suspend fun editChecklist(id: DraftId, itemId: ItemId, entries: List<ChecklistEntry>)
    suspend fun editTextItem(id: DraftId, itemId: ItemId, edit: TextEdit)
    suspend fun editPhotoCaption(id: DraftId, itemId: ItemId, caption: String)
    /** 替换该草稿照片项的有序笔画，空列表清除标注。成功后返回可从草稿重读的照片项。 */
    suspend fun editPhotoStrokes(id: DraftId, itemId: ItemId, strokes: List<PhotoStroke>): ContentItem.Photo
    suspend fun moveItem(id: DraftId, itemId: ItemId, direction: MoveDirection)
    suspend fun removeItem(id: DraftId, itemId: ItemId)
    /** 每份草稿可撤销最近一次移除；完成或放弃草稿后，该操作结束。 */
    suspend fun canUndoRemoval(id: DraftId): Boolean
    suspend fun undoRemoval(id: DraftId): Boolean
    suspend fun completeMixedDraft(id: DraftId): RecordId
    suspend fun completeMixedDraft(id: DraftId, itemId: ItemId, finalEdit: TextEdit): RecordId
}

internal fun newItemId() = ItemId(UUID.randomUUID().toString())
internal fun ContentItem.withId(id: ItemId): ContentItem = when (this) {
    is ContentItem.Text -> copy(id = id)
    is ContentItem.Photo -> copy(id = id)
    is ContentItem.Link -> copy(id = id)
    is ContentItem.Pdf -> copy(id = id)
    is ContentItem.Video -> copy(id = id)
    is ContentItem.Audio -> copy(id = id)
    is ContentItem.Checklist -> copy(id = id)
}

internal fun ContentItem.searchText(): String? = when (this) {
    is ContentItem.Text -> text
    is ContentItem.Checklist -> entries.joinToString("\n") { it.text }
    is ContentItem.Photo, is ContentItem.Audio, is ContentItem.Pdf, is ContentItem.Video, is ContentItem.Link -> null
}
internal fun List<ContentItem>.body() = mapNotNull { it.searchText() }.joinToString("\n")
internal fun List<ContentItem>.photos() = filterIsInstance<ContentItem.Photo>().map { it.photoId }

internal fun ContentSlot.asItem(): ContentItem {
    check(listOf(text, photoId, checklistJson, audioId, pdfId, videoId, linkAddress).count { it != null } == 1) { "内容项类型无效" }
    check((audioId == null) == (audioDurationMillis == null)) { "录音时长缺失或类型无效" }
    check(photoStrokes == null || photoId != null) { "标注缺少对应的照片" }
    return when {
        linkAddress != null -> ContentItem.Link(ItemId(itemId), linkAddress, linkTitle.orEmpty(), linkNote.orEmpty(),
            linkScreenshotId?.let { PhotoId(UUID.fromString(it)) })
        videoId != null -> ContentItem.Video(
            ItemId(itemId), VideoId(UUID.fromString(videoId)), checkNotNull(videoName), checkNotNull(videoSizeBytes), videoDurationMillis,
        )
        pdfId != null -> ContentItem.Pdf(
            ItemId(itemId), PdfId(UUID.fromString(pdfId)), checkNotNull(pdfName), checkNotNull(pdfSizeBytes),
        )
        audioId != null -> ContentItem.Audio(
            ItemId(itemId), AudioId(UUID.fromString(audioId)), checkNotNull(audioDurationMillis), caption,
        )
        checklistJson != null -> ContentItem.Checklist(ItemId(itemId), decodeChecklist(checklistJson))
        photoId != null -> ContentItem.Photo(ItemId(itemId), PhotoId(UUID.fromString(photoId)), caption, photoStrokes.orEmpty())
        else -> ContentItem.Text(ItemId(itemId), checkNotNull(text))
    }
}

internal fun ContentItem.asSlot(): ContentSlot = when (this) {
    is ContentItem.Text -> ContentSlot(id.value, text, null)
    is ContentItem.Photo -> ContentSlot(id.value, null, photoId.value.toString(), caption,
        photoStrokes = strokes.takeIf { it.isNotEmpty() })
    is ContentItem.Link -> ContentSlot(id.value, null, null, linkAddress = address, linkTitle = title,
        linkNote = note, linkScreenshotId = screenshotId?.value?.toString())
    is ContentItem.Pdf -> ContentSlot(
        id.value, null, null, pdfId = pdfId.value.toString(), pdfName = displayName, pdfSizeBytes = sizeBytes,
    )
    is ContentItem.Video -> ContentSlot(
        id.value, null, null, videoId = videoId.value.toString(), videoName = displayName,
        videoSizeBytes = sizeBytes, videoDurationMillis = durationMillis,
    )
    is ContentItem.Checklist -> ContentSlot(id.value, null, null, checklistJson = encodeChecklist(entries))
    is ContentItem.Audio -> ContentSlot(
        id.value, null, null, caption, audioId = audioId.value.toString(), audioDurationMillis = durationMillis,
    )
}

internal fun List<ContentItem>.indexOfItem(id: ItemId): Int =
    indexOfFirst { it.id == id }.also { require(it >= 0) { "草稿中找不到指定内容项" } }

internal fun MixedDraft.withText(id: ItemId, edit: TextEdit): MixedDraft {
    val index = items.indexOfItem(id)
    require(items[index] is ContentItem.Text) { "该项不是文字" }
    val changed = items.toMutableList().apply { set(index, ContentItem.Text(id, edit.text)) }
    return copy(items = changed, activeItemId = id, cursor = edit.cursor)
}

// v3 的接口使用拼接后的正文和全局光标。混排接口使用稳定的项 ID 和项内光标。
private fun List<ContentItem>.textStart(id: ItemId): Int {
    var offset = 0
    for (item in this) {
        if (item.id == id) return offset
        item.searchText()?.let { offset += it.length + 1 }
    }
    error("草稿编辑位置已不存在")
}

internal suspend fun FolioDatabase.loadMixedDraft(id: DraftId): MixedDraft? {
    val row = textDao().draft(id.value) ?: return null
    val items = itemDao().draftItems(id.value).map { it.asItem() }
    val focus = row.activeItemId?.let(::ItemId) ?: items.firstOrNull()?.id
    val active = items.firstOrNull { it.id == focus }
    val cursor = if (active is ContentItem.Text) {
        (row.cursor - items.textStart(active.id)).coerceIn(0, active.text.length)
    } else 0
    return MixedDraft(items, focus, cursor, row.title)
}

/** 调用方在同一 Room 事务中保存有序项、搜索正文和编辑位置。 */
internal suspend fun FolioDatabase.writeMixedDraft(id: DraftId, draft: MixedDraft, timestamp: Long) {
    val body = draft.items.body()
    val cursor = draft.activeItemId?.let { (draft.items.textStart(it) + draft.cursor).coerceAtMost(body.length) } ?: 0
    val contentAt = timestamp.takeIf { draft.items.hasActualContent(body) }
    check(textDao().updateDraft(id.value, body, cursor, draft.activeItemId?.value, timestamp, contentAt) == 1) { "草稿已不存在" }
    itemDao().replaceDraftItems(id.value, draft.items.map { it.asSlot() })
}

internal fun List<ContentItem>.hasActualContent(body: String): Boolean =
    body.isNotBlank() || any { it is ContentItem.Photo || it is ContentItem.Audio || it is ContentItem.Pdf ||
        it is ContentItem.Video || (it is ContentItem.Link && it.hasInput) }
