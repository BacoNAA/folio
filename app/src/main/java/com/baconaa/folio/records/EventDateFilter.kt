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
        val precision get() = RangePrecision(precisionOf(fromInput), precisionOf(toInput.ifEmpty { fromInput }))

        private fun precisionOf(input: String) = when (input.length) {
            4 -> DatePrecision.YEAR
            7 -> DatePrecision.MONTH
            else -> DatePrecision.DAY
        }

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

internal fun EventDateFilter?.forQuery(): EventTimeFilter? = when (this) {
    null -> null
    EventDateFilter.Undated -> EventTimeFilter.Undated
    is EventDateFilter.Range -> EventTimeFilter.Within(start..end)
}
