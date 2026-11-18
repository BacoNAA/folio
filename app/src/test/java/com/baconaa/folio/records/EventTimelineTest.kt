package com.baconaa.folio.records

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTimelineTest {
    private fun card(id: String, time: EventTime?, pinned: Boolean = false) =
        RecordCard(RecordId(id), time, ContentPreview(emptyList(), 0, 0), RecordMarks(isPinned = pinned))

    @Test fun separatesApproximateDatesNamedSeasonsCrossYearRangesAndUnknownDescriptions() {
        val exact = card("exact", EventTime(LocalDate.parse("2025-09-10")))
        val winter = card("winter", EventTime.Season(2025, NamedSeason.WINTER, 12, 2))
        val year = card("year", EventTime.Year(2025), pinned = true)
        val priorYear = card("prior", EventTime.Year(2023))
        val college = card("college", EventTime.Description("大学时", LocalDate.parse("2018-09-01")..LocalDate.parse("2022-06-30")))
        val unknown = card("unknown", EventTime.Description("旅行之前"))
        val empty = card("empty", null)
        val timeline = eventTimeline(listOf(priorYear, winter, college, unknown, exact, year, empty))

        assertEquals(listOf(2025, 2023), timeline.years.map { it.year })
        assertEquals(listOf(exact), timeline.years[0].records)
        assertEquals(listOf(year, winter), timeline.years[0].approximate)
        assertEquals(listOf(priorYear), timeline.years[1].approximate)
        assertEquals(listOf(college), timeline.crossYear)
        assertEquals(listOf(unknown, empty), timeline.undated)
    }
}
