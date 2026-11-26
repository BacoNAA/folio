package com.baconaa.folio.records

import java.io.IOException

/** itemIds 使用副本的内容顺序。线索只引用来源记录上经用户选中的身份。 */
data class RecordCopySelection(
    val itemIds: List<ItemId> = emptyList(),
    val keepTitle: Boolean = true,
    val keepEventTime: Boolean = true,
    val keepChecked: Boolean = true,
    val placeId: PlaceId? = null,
    val tagIds: Set<TagId> = emptySet(),
    val objectIds: Set<ObjectId> = emptySet(),
)

class CopySourceChanged : IllegalStateException("来源记录已更新或移入回收站，请重新查看来源。")

class SplitDraftConflict : IllegalStateException("来源已有未完成的草稿，请先从「继续草稿」完成或放弃，再试一次。")

class CopyMediaUnavailable(cause: IOException) : IOException("所选媒体原件无法读取。请检查来源，或取消勾选相关内容后重试。", cause)

internal fun ContentItem.copyForNewDraft(keepChecked: Boolean): ContentItem = when (this) {
    is ContentItem.Checklist -> copy(id = newItemId(), entries = entries.map {
        it.copy(id = ChecklistEntryId.create(), checked = keepChecked && it.checked)
    })
    else -> withId(newItemId())
}
