package com.baconaa.folio.records

import java.time.LocalDate

/** 切换或移除时间条件时，用 copy(time = ...) 保留其余查找条件。 */
data class RecordQuery(
    val keyword: String = "",
    val tagIds: Set<TagId> = emptySet(),
    val starredOnly: Boolean = false,
    val placeId: PlaceId? = null,
    val peopleIds: Set<ObjectId> = emptySet(),
    val thingIds: Set<ObjectId> = emptySet(),
    val mediaTypes: Set<MediaFilter> = emptySet(),
    val time: EventTimeFilter? = null,
    val feeling: FeelingChoice? = null,
)

sealed interface EventTimeFilter {
    /** 使用事件所在地的日历日期，包含首尾两天。 */
    data class Within(val dates: ClosedRange<LocalDate>) : EventTimeFilter {
        init { require(!dates.isEmpty()) { "筛选结束日期不能早于开始日期" } }
    }

    data object Undated : EventTimeFilter
}

/** 先展示 certain 再展示 possible，两组各自保留置顶及最近记录顺序。
 * undatedCount 只在范围查询时统计可另行查看的未定记录，不计入两组结果。
 */
data class RecordSearchResult(
    val certain: List<RecordCard>,
    val possible: List<RecordCard>,
    val undatedCount: Int,
)
