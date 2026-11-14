package com.baconaa.folio.records

import androidx.room.withTransaction

enum class MoveDirection { UP, DOWN }

internal class ContentItemActions(
    private val database: FolioDatabase,
    private val now: () -> Long,
) {
    private val removals = database.removedItemDao()

    suspend fun move(draftId: DraftId, itemId: ItemId, direction: MoveDirection) {
        database.withTransaction {
            val draft = checkNotNull(database.loadMixedDraft(draftId)) { "草稿已不存在" }
            val from = draft.items.indexOfItem(itemId)
            val to = if (direction == MoveDirection.UP) from - 1 else from + 1
            if (to !in draft.items.indices) return@withTransaction
            val items = draft.items.toMutableList()
            val moved = items.removeAt(from)
            items.add(to, moved)
            database.writeMixedDraft(draftId, MixedDraft(items, itemId, draft.cursorFor(moved)), now())
        }
    }

    suspend fun remove(draftId: DraftId, itemId: ItemId) {
        database.withTransaction {
            val draft = checkNotNull(database.loadMixedDraft(draftId)) { "草稿已不存在" }
            val index = draft.items.indexOfItem(itemId)
            val items = draft.items.toMutableList()
            val removed = items.removeAt(index)
            val previous = items.getOrNull(index - 1)
            val next = items.getOrNull(index)
            removals.save(RemovedItem(
                draftId.value, removed.asSlot(), index, previous?.id?.value, next?.id?.value, draft.cursorFor(removed),
            ))
            val focus = next ?: previous
            database.writeMixedDraft(draftId, MixedDraft(items, focus?.id, draft.cursorFor(focus)), now())
        }
    }

    suspend fun canUndo(draftId: DraftId): Boolean = removals.available(draftId.value)

    suspend fun undo(draftId: DraftId): Boolean = database.withTransaction {
        val removed = removals.get(draftId.value) ?: return@withTransaction false
        val draft = database.loadMixedDraft(draftId) ?: return@withTransaction false
        val item = removed.slot.asItem()
        val items = draft.items.toMutableList()
        items.add(removed.restoreIndex(items), item)
        database.writeMixedDraft(draftId, MixedDraft(items, item.id, removed.cursor), now())
        removals.clear(draftId.value)
        true
    }

    private fun MixedDraft.cursorFor(item: ContentItem?): Int = when {
        item !is ContentItem.Text -> 0
        item.id == activeItemId -> cursor
        else -> item.text.length
    }

    // 同一份草稿还可继续插入及移动其他项，用相邻项的身份定位移除处。
    private fun RemovedItem.restoreIndex(items: List<ContentItem>): Int {
        val next = items.indexOfFirst { it.id.value == nextItemId }
        if (next >= 0) return next
        val previous = items.indexOfFirst { it.id.value == previousItemId }
        if (previous >= 0) return previous + 1
        return position.coerceAtMost(items.size)
    }
}
