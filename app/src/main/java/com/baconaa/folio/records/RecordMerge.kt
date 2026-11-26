package com.baconaa.folio.records

/** 项身份与其来源一起标识预览中的内容，列表顺序即新草稿顺序。 */
data class MergeItem(val source: RecordId, val itemId: ItemId)

data class RecordMergeSelection(
    val items: List<MergeItem>,
    val eventTimeSource: RecordId? = null,
    val placeId: PlaceId? = null,
    val tagIds: Set<TagId> = emptySet(),
    val objectIds: Set<ObjectId> = emptySet(),
    val keepChecked: Boolean = true,
)
