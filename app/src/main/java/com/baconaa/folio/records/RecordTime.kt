package com.baconaa.folio.records

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 保留事件当地的日期与可选时刻；回看时不随设备时区转换。null 事件时间表示未填写。 */
data class EventTime(
    val date: LocalDate,
    val time: LocalTime? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class DraftTimes(val firstRecordedAt: Long, val eventTime: EventTime?)

/** 自动时间均为 Unix 毫秒，事件时间保持用户填写的当地表达。 */
data class RecordTimes(val firstRecordedAt: Long, val modifiedAt: Long, val eventTime: EventTime?)

interface RecordTimeEditing {
    suspend fun openDraftTimes(id: DraftId): DraftTimes?

    /** 只保存可续写草稿的时间；传 null 清空。正式时间随完成生效，失败向调用方抛出。 */
    suspend fun saveDraftEventTime(id: DraftId, eventTime: EventTime?)
}

internal data class EventTimeFields(val eventDate: Long, val eventClock: Long?, val eventZone: String) {
    fun asEventTime() = EventTime(LocalDate.ofEpochDay(eventDate), eventClock?.let(LocalTime::ofNanoOfDay), ZoneId.of(eventZone))
}

internal fun EventTime.asFields() = EventTimeFields(date.toEpochDay(), time?.toNanoOfDay(), zone.id)
internal fun RecordRow.times() = RecordTimes(firstRecordedAt, modifiedAt, eventTime?.asEventTime())
