package com.baconaa.folio.records

import androidx.room.withTransaction
import com.baconaa.folio.media.PhotoId

/** 仅供展示，与可回存的 ContentItem 分开；文字各截取前 80 个 Unicode 码点。 */
sealed interface PreviewContentItem {
    val id: ItemId

    data class Text(override val id: ItemId, val text: String) : PreviewContentItem
    data class Photo(override val id: ItemId, val photoId: PhotoId, val caption: String) : PreviewContentItem
    data class Checklist(
        override val id: ItemId,
        val entries: List<ChecklistEntryPreview>,
        val totalEntryCount: Int,
        val checkedEntryCount: Int,
    ) : PreviewContentItem
}

data class ChecklistEntryPreview(val text: String, val checked: Boolean)

/** 最多三项；清单只读展示前三条，同时给出全部事项及已完成事项数。 */
data class ContentPreview(
    val items: List<PreviewContentItem>,
    val remainingItemCount: Int,
    val remainingPhotoCount: Int,
)

data class RecordCard(val id: RecordId, val eventTime: EventTime?, val preview: ContentPreview)
data class EventYear(val year: Int, val records: List<RecordCard>)
data class EventTimeline(val years: List<EventYear>, val undated: List<RecordCard>)

/** 返回有效正式记录的全量列表。读取失败抛出异常；无记录时返回空集合。 */
interface RecordBrowsing {
    /** 按首次记录时间降序，时间相同按入库顺序降序；卡片仍保留事件时间。 */
    suspend fun listRecentRecords(): List<RecordCard>

    /**
     * 按事件当地年份和日期降序，留空单列。日期相同沿用最近记录顺序作为稳定展示约定，
     * 不推断有时刻与无时刻记录的实际先后。未填时间也沿用最近记录顺序。
     */
    suspend fun listEventTimeline(): EventTimeline
}

// SQLite trim 的默认空白只有空格，需与 Kotlin 文字空白判断一致，避免换行项占据预览。
private const val PREVIEW_BLANK_CHARACTERS =
    "\t\n\u000b\u000c\r\u001c\u001d\u001e\u001f \u00a0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000"

internal class LocalRecordBrowsing(private val database: FolioDatabase) : RecordBrowsing {
    private val dao = database.recordBrowsingDao()

    override suspend fun listRecentRecords(): List<RecordCard> = database.withTransaction {
        val records = dao.activeRecords()
        val items = dao.previewItems(PREVIEW_BLANK_CHARACTERS).groupBy { it.recordId }
        val counts = dao.previewCounts(PREVIEW_BLANK_CHARACTERS).associateBy { it.recordId }
        records.map { record ->
            val previewItems = items[record.id].orEmpty().map { it.slot.asPreview() }
            val count = counts[record.id]
            RecordCard(
                RecordId(record.id), record.eventTime?.asEventTime(),
                ContentPreview(
                    previewItems,
                    (count?.itemCount ?: 0) - previewItems.size,
                    (count?.photoCount ?: 0) - previewItems.count { it is PreviewContentItem.Photo },
                ),
            )
        }
    }

    override suspend fun listEventTimeline(): EventTimeline {
        val records = listRecentRecords()
        val dated = records.filter { it.eventTime != null }.sortedByDescending { it.eventTime!!.date }
        return EventTimeline(
            dated.groupBy { it.eventTime!!.date.year }.map { (year, cards) -> EventYear(year, cards) },
            records.filter { it.eventTime == null },
        )
    }
}

private fun ContentSlot.asPreview(): PreviewContentItem = when (val item = asItem()) {
    is ContentItem.Text -> PreviewContentItem.Text(item.id, item.text)
    is ContentItem.Photo -> PreviewContentItem.Photo(item.id, item.photoId, item.caption)
    is ContentItem.Checklist -> PreviewContentItem.Checklist(
        item.id,
        item.entries.take(3).map { ChecklistEntryPreview(it.text.previewText(), it.checked) },
        item.entries.size,
        item.entries.count { it.checked },
    )
}

private fun String.previewText(): String = substring(0, offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(80)))
