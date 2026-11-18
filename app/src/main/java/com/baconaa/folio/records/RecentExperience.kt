package com.baconaa.folio.records

import java.time.Instant

sealed interface RecentExperience {
    data object Empty : RecentExperience
    data class Known(val eventTime: EventTime) : RecentExperience
    data class Uncertain(val undatedCount: Int) : RecentExperience
}

/** 只有一个事件的最早可能时刻不早于其余事件的最晚时刻，才确定最新经历。
 * 日期范围覆盖首尾两天；重叠时间、夏令时重复时刻和未定时间不强排顺序。 */
internal fun recentExperience(times: List<EventTime?>): RecentExperience {
    if (times.isEmpty()) return RecentExperience.Empty
    val undated = times.count { it?.dateRange == null }
    if (undated > 0) return RecentExperience.Uncertain(undated)
    val known = times.filterNotNull()
    val ranges = known.map { it.bounds() ?: return RecentExperience.Uncertain(0) }
    val candidates = ranges.indices.filter { candidate ->
        ranges.indices.all { other -> other == candidate || ranges[candidate].first >= ranges[other].second }
    }
    // 同一完整时间表达可直接显示；不在范围重叠时挑选任意一条。
    return if (candidates.isNotEmpty() && candidates.map { known[it] }.distinct().size == 1) {
        RecentExperience.Known(known[candidates.first()])
    } else RecentExperience.Uncertain(0)
}

private fun EventTime.bounds(): Pair<Instant, Instant>? {
    if (this !is EventTime.Exact || time == null) {
        val range = dateRange ?: return null
        return range.start.atStartOfDay(zone).toInstant() to
            range.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
    }
    val local = date.atTime(time)
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.isEmpty()) return null // 夏令时跳过的当地时刻没有对应瞬间。
    val instants = offsets.map { local.toInstant(it) }
    return instants.min() to instants.max()
}
