package com.baconaa.folio.records

import com.baconaa.folio.media.PhotoId
import java.util.UUID

@JvmInline
value class ItemId(val value: String)

sealed interface ContentItem {
    val id: ItemId

    data class Text(override val id: ItemId, val text: String) : ContentItem
    data class Photo(override val id: ItemId, val photoId: PhotoId, val caption: String = "") : ContentItem
}

/** cursor 是当前文字项内的 UTF-16 偏移。照片项或空草稿使用 0。 */
data class MixedDraft(val items: List<ContentItem>, val activeItemId: ItemId?, val cursor: Int)

interface MixedContentDrafts {
    suspend fun openMixedDraft(id: DraftId): MixedDraft?

    /** after 为 null 时追加到末尾，有值时紧接指定项插入。 */
    suspend fun insertText(id: DraftId, after: ItemId?, edit: TextEdit): ItemId
    suspend fun insertPhoto(id: DraftId, after: ItemId?, photo: PhotoId, caption: String = ""): ItemId
    suspend fun editTextItem(id: DraftId, itemId: ItemId, edit: TextEdit)
    suspend fun editPhotoCaption(id: DraftId, itemId: ItemId, caption: String)
    suspend fun completeMixedDraft(id: DraftId): RecordId
    suspend fun completeMixedDraft(id: DraftId, itemId: ItemId, finalEdit: TextEdit): RecordId
}

internal fun newItemId() = ItemId(UUID.randomUUID().toString())
internal fun List<ContentItem>.body() = filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
internal fun List<ContentItem>.photos() = filterIsInstance<ContentItem.Photo>().map { it.photoId }

internal fun ContentSlot.asItem(): ContentItem = if (photoId == null) {
    ContentItem.Text(ItemId(itemId), checkNotNull(text))
} else {
    ContentItem.Photo(ItemId(itemId), PhotoId(UUID.fromString(photoId)), caption)
}

internal fun ContentItem.asSlot(): ContentSlot = when (this) {
    is ContentItem.Text -> ContentSlot(id.value, text, null)
    is ContentItem.Photo -> ContentSlot(id.value, null, photoId.value.toString(), caption)
}

internal fun List<ContentItem>.indexOfItem(id: ItemId): Int =
    indexOfFirst { it.id == id }.also { require(it >= 0) { "草稿中找不到指定内容项" } }

internal fun MixedDraft.withText(id: ItemId, edit: TextEdit): MixedDraft {
    val index = items.indexOfItem(id)
    require(items[index] is ContentItem.Text) { "该项不是文字" }
    val changed = items.toMutableList().apply { set(index, ContentItem.Text(id, edit.text)) }
    return MixedDraft(changed, id, edit.cursor)
}

// v3 的接口使用拼接后的正文和全局光标。混排接口使用稳定的项 ID 和项内光标。
private fun List<ContentItem>.textStart(id: ItemId): Int {
    var offset = 0
    for (item in this) {
        if (item.id == id) return offset
        if (item is ContentItem.Text) offset += item.text.length + 1
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
    return MixedDraft(items, focus, cursor)
}

/** 调用方在同一 Room 事务中保存有序项、搜索正文和编辑位置。 */
internal suspend fun FolioDatabase.writeMixedDraft(id: DraftId, draft: MixedDraft, timestamp: Long) {
    val body = draft.items.body()
    val cursor = draft.activeItemId?.let { (draft.items.textStart(it) + draft.cursor).coerceAtMost(body.length) } ?: 0
    check(textDao().updateDraft(id.value, body, cursor, draft.activeItemId?.value, timestamp) == 1) { "草稿已不存在" }
    itemDao().replaceDraftItems(id.value, draft.items.map { it.asSlot() })
}
