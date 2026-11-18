package com.baconaa.folio.records

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EventTimeExpressionTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test fun calendarPrecisionSurvivesStorageIncludingLeapFebruary() {
        val year = EventTime.Year(2024, zone)
        val month = EventTime.Month(YearMonth.of(2024, 2), zone)
        val range = EventTime.Range(LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 29), zone)
        assertEquals(LocalDate.of(2024, 1, 1)..LocalDate.of(2024, 12, 31), year.dateRange)
        assertEquals(LocalDate.of(2024, 2, 1)..LocalDate.of(2024, 2, 29), month.dateRange)
        assertEquals(2024, month.groupingYear)
        assertNull(range.groupingYear)
        for (value in listOf(year, month, range)) assertEquals(value, value.asFields().asEventTime())
    }

    @Test fun storedSeasonKeepsItsOriginalMonthsAndNamedYear() {
        val normal = EventTime.Season(2023, NamedSeason.WINTER, zone = zone)
        val customized = normal.copy(firstMonth = 11, lastMonth = 3)
        val restored = customized.asFields().asEventTime()

        assertEquals(LocalDate.of(2023, 12, 1)..LocalDate.of(2024, 2, 29), normal.dateRange)
        assertEquals(LocalDate.of(2023, 11, 1)..LocalDate.of(2024, 3, 31), restored.dateRange)
        assertEquals(2023, restored.groupingYear)
        assertEquals("2023 年冬天", restored.label)
        assertEquals(customized, restored)
    }

    @Test fun descriptionPreservesWordingWithOrWithoutKnownDates() {
        val unknown = EventTime.Description("大学时", zone = zone)
        val known = unknown.copy(dateRange = LocalDate.of(2018, 9, 1)..LocalDate.of(2022, 6, 30))
        assertNull(unknown.asFields().asEventTime().dateRange)
        assertEquals("大学时", known.asFields().asEventTime().label)
        assertEquals(known, known.asFields().asEventTime())
        assertThrows(IllegalArgumentException::class.java) { unknown.copy(text = " ") }
        val backwards = LocalDate.of(2022, 1, 1)..LocalDate.of(2018, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { unknown.copy(dateRange = backwards) }
        assertThrows(IllegalArgumentException::class.java) { EventTime.Range(backwards.start, backwards.endInclusive) }
    }

    @Test fun exactDateRetainsLegacyFieldsAndLocalClock() {
        val date = LocalDate.of(2025, 11, 8)
        val time = LocalTime.of(0, 30)
        val fields = EventTimeFields(date.toEpochDay(), time.toNanoOfDay(), zone.id)
        assertEquals(EventTime(date, time, zone), fields.asEventTime())
        assertEquals(fields, fields.asEventTime().asFields())
        assertEquals(date..date, fields.asEventTime().dateRange)
    }
}
