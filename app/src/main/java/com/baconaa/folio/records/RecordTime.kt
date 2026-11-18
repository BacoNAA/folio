package com.baconaa.folio.records

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

enum class NamedSeason(val label: String, val firstMonth: Int, val lastMonth: Int) {
    SPRING("春天", 3, 5), SUMMER("夏天", 6, 8), AUTUMN("秋天", 9, 11), WINTER("冬天", 12, 2),
}

/** 日期范围包含首尾两天。未填写用 null 表示，自由描述没有已知范围时仍保留原话。 */
sealed interface EventTime {
    val zone: ZoneId
    val label: String
    val dateRange: ClosedRange<LocalDate>?
    val groupingYear: Int?
        get() = dateRange?.let { it.start.year.takeIf { year -> year == it.endInclusive.year } }

    data class Exact(val date: LocalDate, val time: LocalTime? = null, override val zone: ZoneId = ZoneId.systemDefault()) : EventTime {
        override val label get() = "$date${time?.let { " $it" }.orEmpty()} · ${zone.id}"
        override val dateRange get() = date..date
    }

    data class Year(val year: Int, override val zone: ZoneId = ZoneId.systemDefault()) : EventTime {
        override val label get() = "${year} 年"
        override val dateRange = LocalDate.of(year, 1, 1)..LocalDate.of(year, 12, 31)
    }

    data class Month(val month: YearMonth, override val zone: ZoneId = ZoneId.systemDefault()) : EventTime {
        override val label get() = "${month.year} 年 ${month.monthValue} 月"
        override val dateRange = month.atDay(1)..month.atEndOfMonth()
    }

    data class Season(
        val year: Int,
        val season: NamedSeason,
        val firstMonth: Int = season.firstMonth,
        val lastMonth: Int = season.lastMonth,
        override val zone: ZoneId = ZoneId.systemDefault(),
    ) : EventTime {
        override val label get() = "${year} 年${season.label}"
        override val groupingYear get() = year
        override val dateRange = LocalDate.of(year, firstMonth, 1)..
            YearMonth.of(if (lastMonth < firstMonth) year + 1 else year, lastMonth).atEndOfMonth()
    }

    data class Range(val start: LocalDate, val endInclusive: LocalDate, override val zone: ZoneId = ZoneId.systemDefault()) : EventTime {
        init { require(start <= endInclusive) { "结束日期不能早于开始日期" } }
        override val label get() = "$start — $endInclusive"
        override val dateRange get() = start..endInclusive
    }

    data class Description(
        val text: String,
        override val dateRange: ClosedRange<LocalDate>? = null,
        override val zone: ZoneId = ZoneId.systemDefault(),
    ) : EventTime {
        init {
            require(text.isNotBlank()) { "请填写时间描述" }
            require(dateRange == null || !dateRange.isEmpty()) { "结束日期不能早于开始日期" }
        }
        override val label get() = text
    }

    companion object {
        operator fun invoke(date: LocalDate, time: LocalTime? = null, zone: ZoneId = ZoneId.systemDefault()) =
            Exact(date, time, zone)
    }
}

/** 首次保存实际内容前，只有标题的草稿尚无首次记录时间。 */
data class DraftTimes(val firstRecordedAt: Long?, val eventTime: EventTime?)

/** 自动时间均为 Unix 毫秒，事件时间保持用户填写的当地表达。 */
data class RecordTimes(val firstRecordedAt: Long, val modifiedAt: Long, val eventTime: EventTime?)

interface RecordTimeEditing {
    suspend fun openDraftTimes(id: DraftId): DraftTimes?

    /** 只保存可续写草稿的时间；传 null 清空。正式时间随完成生效，失败向调用方抛出。 */
    suspend fun saveDraftEventTime(id: DraftId, eventTime: EventTime?)
}

/** eventKind 为空是 v14 的精确日期格式。季节保存填写时的起止日期，以后修改季节设置时仍能还原。 */
internal data class EventTimeFields(
    val eventDate: Long?,
    val eventClock: Long?,
    val eventZone: String,
    val eventKind: String? = null,
    val eventEndDate: Long? = null,
    val eventText: String? = null,
    val eventSeasonYear: Int? = null,
) {
    fun asEventTime(): EventTime {
        val zone = ZoneId.of(eventZone)
        val start = eventDate?.let(LocalDate::ofEpochDay)
        val end = eventEndDate?.let(LocalDate::ofEpochDay)
        return when (eventKind) {
            null -> EventTime(requireNotNull(start), eventClock?.let(LocalTime::ofNanoOfDay), zone)
            "year" -> EventTime.Year(requireNotNull(start).year, zone)
            "month" -> EventTime.Month(YearMonth.from(requireNotNull(start)), zone)
            "season" -> EventTime.Season(requireNotNull(eventSeasonYear), NamedSeason.valueOf(requireNotNull(eventText)),
                requireNotNull(start).monthValue, requireNotNull(end).monthValue, zone)
            "range" -> EventTime.Range(requireNotNull(start), requireNotNull(end), zone)
            "description" -> EventTime.Description(requireNotNull(eventText), start?.let { it..requireNotNull(end) }, zone)
            else -> error("未知事件时间类型：$eventKind")
        }
    }
}

internal fun EventTime.asFields(): EventTimeFields {
    val start = dateRange?.start?.toEpochDay()
    val end = dateRange?.endInclusive?.toEpochDay()
    return when (this) {
        is EventTime.Exact -> EventTimeFields(start, time?.toNanoOfDay(), zone.id)
        is EventTime.Year -> EventTimeFields(start, null, zone.id, "year", end)
        is EventTime.Month -> EventTimeFields(start, null, zone.id, "month", end)
        is EventTime.Season -> EventTimeFields(start, null, zone.id, "season", end, season.name, year)
        is EventTime.Range -> EventTimeFields(start, null, zone.id, "range", end)
        is EventTime.Description -> EventTimeFields(start, null, zone.id, "description", end, text)
    }
}

internal fun RecordRow.times() = RecordTimes(firstRecordedAt, modifiedAt, eventTime?.asEventTime())
