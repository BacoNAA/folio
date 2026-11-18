package com.baconaa.folio.records

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

sealed interface EventDateFilter {
    data object Undated : EventDateFilter

    class Range private constructor(
        val fromInput: String, val toInput: String,
        val start: LocalDate, val end: LocalDate,
    ) : EventDateFilter {
        companion object {
            /** 单端取完整年月或当天；范围起止分别按输入精度扩展到首日和末日。 */
            fun parse(fromInput: String, toInput: String = ""): Range? {
                val from = fromInput.trim()
                val to = toInput.trim()
                val start = boundary(from, false) ?: return null
                val end = boundary(to.ifEmpty { from }, true) ?: return null
                return if (end >= start) Range(from, to, start, end) else null
            }

            private fun boundary(input: String, last: Boolean): LocalDate? {
                val year = input.take(4).toIntOrNull() ?: return null
                if (year !in 1000..9999) return null
                return try {
                    when (input.length) {
                        4 -> LocalDate.of(year, if (last) 12 else 1, if (last) 31 else 1)
                        7 -> YearMonth.parse(input).let { if (last) it.atEndOfMonth() else it.atDay(1) }
                        10 -> LocalDate.parse(input)
                        else -> null
                    }
                } catch (_: DateTimeException) {
                    null
                }
            }
        }
    }
}

internal data class EventDateMatches(val records: List<RecordCard>, val undatedCount: Int = 0)

/** 输入为已满足其他查找条件的同一批正式记录，保留原排序。 */
internal fun matchEventDates(records: List<RecordCard>, filter: EventDateFilter?): EventDateMatches = when (filter) {
    null -> EventDateMatches(records)
    EventDateFilter.Undated -> EventDateMatches(records.filter { it.eventTime == null })
    is EventDateFilter.Range -> {
        var undated = 0
        val matches = records.filter { card ->
            val date = card.eventTime?.date
            if (date == null) {
                undated++
                false
            } else date in filter.start..filter.end
        }
        EventDateMatches(matches, undated)
    }
}
