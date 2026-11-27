package com.baconaa.folio.records

import androidx.room.withTransaction
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.media.AudioId

/** 仅供展示，与可回存的 ContentItem 分开；文字各截取前 80 个 Unicode 码点。 */
sealed interface PreviewContentItem {
    val id: ItemId

    data class Text(override val id: ItemId, val text: String) : PreviewContentItem
    data class Photo(override val id: ItemId, val photoId: PhotoId, val caption: String) : PreviewContentItem
    data class Pdf(override val id: ItemId, val displayName: String) : PreviewContentItem
    data class Link(override val id: ItemId, val label: String) : PreviewContentItem
    data class Video(override val id: ItemId, val displayName: String, val durationMillis: Long?) : PreviewContentItem
    data class Audio(
        override val id: ItemId, val audioId: AudioId, val durationMillis: Long, val caption: String,
    ) : PreviewContentItem
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
    val remainingAudioCount: Int = 0,
)

data class RecordCard(
    val id: RecordId, val eventTime: EventTime?, val preview: ContentPreview,
    val marks: RecordMarks = RecordMarks(),
    val tags: List<Tag> = emptyList(),
    val place: Place? = null,
    val objects: List<RecordObject> = emptyList(),
    val title: String? = null,
)
data class EventYear(val year: Int, val records: List<RecordCard>, val approximate: List<RecordCard> = emptyList())
data class EventTimeline(val years: List<EventYear>, val undated: List<RecordCard>, val crossYear: List<RecordCard> = emptyList())

enum class MediaFilter { PHOTO, AUDIO, VIDEO, ATTACHMENT, LINK, CHECKLIST, TEXT_ONLY }

/** 返回有效正式记录的全量列表。读取失败抛出异常；无记录时返回空集合。 */
interface RecordBrowsing {
    /** 置顶优先，其余按首次记录时间降序，时间相同按入库顺序降序。 */
    suspend fun listRecentRecords(): List<RecordCard>

    /** 只显示已加星的有效正式记录，排序及内容预览沿用最近记录。 */
    suspend fun listStarredRecords(): List<RecordCard>

    /** 标签、人物、物件、内容类型各自多选满足任一，各类别及关键词、星标、地点同时满足。仅查有效正式内容。 */
    suspend fun queryRecords(
        keyword: String = "", tagIds: Set<TagId> = emptySet(), starredOnly: Boolean = false, placeId: PlaceId? = null,
        peopleIds: Set<ObjectId> = emptySet(), thingIds: Set<ObjectId> = emptySet(),
        mediaTypes: Set<MediaFilter> = emptySet(),
    ): List<RecordCard> = queryRecords(
        RecordQuery(keyword, tagIds, starredOnly, placeId, peopleIds, thingIds, mediaTypes),
    ).certain

    /** 限定事件时间时分别返回明确与可能符合的记录。未限定时间时所有命中均在 certain。 */
    suspend fun queryRecords(query: RecordQuery): RecordSearchResult

    /**
     * 年份降序，年内具体日期与大概时间分组；普通跨年范围和时间未定另列。
     * 各组置顶优先，具体日期按当地日期降序。大概时间保留输入顺序，不推断先后。
     */
    suspend fun listEventTimeline(): EventTimeline
}

// SQLite trim 的默认空白只有空格，需与 Kotlin 文字空白判断一致，避免换行项占据预览。
private const val PREVIEW_BLANK_CHARACTERS =
    "\t\n\u000b\u000c\r\u001c\u001d\u001e\u001f \u00a0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000"

// 给预览等附加参数留出余量，兼容旧版 SQLite 每条语句的绑定参数上限。
internal const val RECORD_ID_BATCH_SIZE = 900

internal class LocalRecordBrowsing(private val database: FolioDatabase) : RecordBrowsing {
    private val dao = database.recordBrowsingDao()

    override suspend fun listRecentRecords(): List<RecordCard> = queryRecords()

    override suspend fun listStarredRecords(): List<RecordCard> = queryRecords(starredOnly = true)

    override suspend fun queryRecords(query: RecordQuery): RecordSearchResult = database.withTransaction {
        val active = dao.activeRecords(
            query.keyword, query.tagIds.map { it.value }, query.tagIds.isEmpty(), query.starredOnly, query.placeId?.value,
            query.peopleIds.map { it.value }, query.peopleIds.isEmpty(), query.thingIds.map { it.value }, query.thingIds.isEmpty(),
            allMedia = query.mediaTypes.isEmpty(), photo = MediaFilter.PHOTO in query.mediaTypes,
            audio = MediaFilter.AUDIO in query.mediaTypes, video = MediaFilter.VIDEO in query.mediaTypes,
            attachment = MediaFilter.ATTACHMENT in query.mediaTypes, checklist = MediaFilter.CHECKLIST in query.mediaTypes,
            link = MediaFilter.LINK in query.mediaTypes,
            textOnly = MediaFilter.TEXT_ONLY in query.mediaTypes,
        )
        val records = query.feeling?.let { value ->
            val ids = database.feelingDao().matches(value.scaleId.value, value.level, value.total, value.label).toSet()
            active.filter { it.id in ids }
        } ?: active
        val dates = when (val filter = query.time) {
            null -> return@withTransaction RecordSearchResult(readCards(records), emptyList(), 0)
            EventTimeFilter.Undated -> {
                val undated = records.filter { it.eventTime?.asEventTime()?.dateRange == null }
                return@withTransaction RecordSearchResult(readCards(undated), emptyList(), 0)
            }
            is EventTimeFilter.Within -> filter.dates
        }
        val certain = mutableListOf<BrowseRecordRow>()
        val possible = mutableListOf<BrowseRecordRow>()
        var undatedCount = 0
        for (record in records) {
            val range = record.eventTime?.asEventTime()?.dateRange
            when {
                range == null -> undatedCount++
                range.start in dates && range.endInclusive in dates -> certain.add(record)
                range.start <= dates.endInclusive && dates.start <= range.endInclusive -> possible.add(record)
            }
        }
        val cards = readCards(certain + possible)
        RecordSearchResult(cards.subList(0, certain.size), cards.subList(certain.size, cards.size), undatedCount)
    }

    suspend fun readCards(records: List<BrowseRecordRow>): List<RecordCard> =
        records.chunked(RECORD_ID_BATCH_SIZE).flatMap { readCardBatch(it) }

    private suspend fun readCardBatch(records: List<BrowseRecordRow>): List<RecordCard> {
        val ids = records.map { it.id }
        val items = dao.previewItems(ids, PREVIEW_BLANK_CHARACTERS).groupBy { it.recordId }
        val counts = dao.previewCounts(ids, PREVIEW_BLANK_CHARACTERS).associateBy { it.recordId }
        val tags = database.tagDao().activeRecordTags(ids).groupBy { it.recordId }
        val places = database.placeDao().activeRecordPlaces(ids).associate { it.recordId to it.place.asPlace() }
        val objects = database.objectDao().activeRecordObjects(ids).groupBy { it.recordId }
        return records.map { record ->
            val previewItems = items[record.id].orEmpty().map { it.slot.asPreview() }
            val count = counts[record.id]
            RecordCard(
                RecordId(record.id), record.eventTime?.asEventTime(),
                ContentPreview(
                    previewItems,
                    (count?.itemCount ?: 0) - previewItems.size,
                    (count?.photoCount ?: 0) - previewItems.count { it is PreviewContentItem.Photo },
                    (count?.audioCount ?: 0) - previewItems.count { it is PreviewContentItem.Audio },
                ),
                record.marks,
                tags[record.id].orEmpty().map { it.tag.asTag() },
                places[record.id],
                objects[record.id].orEmpty().map { it.details.asObject() },
                record.title,
            )
        }
    }

    override suspend fun listEventTimeline(): EventTimeline = eventTimeline(listRecentRecords())
}

/** 时间流与地点小册采用相同的归年规则；跨年季节属于填写年，普通跨年范围单列。 */
fun eventTimeline(records: List<RecordCard>): EventTimeline {
    val byYear = records.groupBy { it.eventTime?.groupingYear }
    val years = byYear.keys.filterNotNull().sortedDescending().map { year ->
        val (exact, approximate) = byYear.getValue(year).partition { it.eventTime is EventTime.Exact }
        val exactOrder = compareByDescending<RecordCard> { it.marks.isPinned }
            .thenByDescending { (it.eventTime as EventTime.Exact).date }
        EventYear(year, exact.sortedWith(exactOrder), approximate.sortedByDescending { it.marks.isPinned })
    }
    val (undated, crossYear) = byYear[null].orEmpty().partition { it.eventTime?.dateRange == null }
    return EventTimeline(years, undated.sortedByDescending { it.marks.isPinned }, crossYear.sortedByDescending { it.marks.isPinned })
}

private fun ContentSlot.asPreview(): PreviewContentItem = when (val item = asItem()) {
    is ContentItem.EmptyMediaField -> error("空模板字段不能作为记录预览")
    is ContentItem.Text -> PreviewContentItem.Text(item.id, item.text)
    is ContentItem.Photo -> PreviewContentItem.Photo(item.id, item.photoId, item.caption)
    is ContentItem.Pdf -> PreviewContentItem.Pdf(item.id, item.displayName)
    is ContentItem.Link -> PreviewContentItem.Link(item.id, item.title.ifBlank { item.address }.previewText())
    is ContentItem.Video -> PreviewContentItem.Video(item.id, item.displayName, item.durationMillis)
    is ContentItem.Audio -> PreviewContentItem.Audio(item.id, item.audioId, item.durationMillis, item.caption)
    is ContentItem.Checklist -> PreviewContentItem.Checklist(
        item.id,
        item.entries.take(3).map { ChecklistEntryPreview(it.text.previewText(), it.checked) },
        item.entries.size,
        item.entries.count { it.checked },
    )
}

private fun String.previewText(): String = substring(0, offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(80)))
