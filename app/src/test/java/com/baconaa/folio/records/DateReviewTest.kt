package com.baconaa.folio.records

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DateReviewTest {
    @Test fun sameDayUsesOriginalCalendarAndOnlyEarlierYearsWithExplicitDay() {
        val leap = DateReviewQuery(LocalDate.parse("2024-02-29"))
        assertNotNull(EventTime.Exact(LocalDate.parse("2020-02-29"), zone = ZoneId.of("Pacific/Kiritimati")).reviewReason(leap, null))
        assertNull(EventTime.Exact(leap.date).reviewReason(leap, null))
        assertNull(EventTime.Exact(LocalDate.parse("2023-02-28")).reviewReason(leap, null))
        assertNull(EventTime.Year(2020).reviewReason(leap, null))
    }

    @Test fun winterUsesSavedRangesAndOnlyFinishedSeasons() {
        val query = DateReviewQuery(LocalDate.parse("2026-01-10"), NamedSeason.WINTER)
        val winter = SeasonMonths(12, 2)
        assertNull(EventTime.Exact(LocalDate.parse("2025-12-31")).reviewReason(query, winter))
        assertNotNull(EventTime.Exact(LocalDate.parse("2025-01-31")).reviewReason(query, winter))
        val earlier = EventTime.Season(2024, NamedSeason.WINTER, firstMonth = 11)
        assertTrue(earlier.reviewReason(query, winter)!!.startsWith("可能符合"))
        assertNull(EventTime.Description("大学时").reviewReason(query, winter))
        val range = EventTime.Range(LocalDate.parse("1980-01-01"), LocalDate.parse("2030-01-01"))
        assertTrue(range.reviewReason(query, winter)!!.contains("部分重合"))
    }
}
