package com.baconaa.folio.records

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class EventDateFilterTest {
    @Test fun expandsEachEndpointAtItsOwnPrecision() {
        fun check(from: String, to: String, start: String, end: String) {
            val range = requireNotNull(EventDateFilter.Range.parse(from, to))
            assertEquals(LocalDate.parse(start), range.start)
            assertEquals(LocalDate.parse(end), range.end)
        }
        check("2024", "", "2024-01-01", "2024-12-31")
        check("2024-02", "", "2024-02-01", "2024-02-29")
        check("2024-02-29", "", "2024-02-29", "2024-02-29")
        check("2024-12-31", "2025-02", "2024-12-31", "2025-02-28")
        for (bad in listOf("", "23", "2025-02-29", "2024-13", "20250")) {
            assertNull(EventDateFilter.Range.parse(bad))
        }
        assertNull(EventDateFilter.Range.parse("2025", "2024"))
    }

    @Test fun matchesBothBoundariesAndCountsUndatedSeparately() {
        fun card(id: String, date: String?) = RecordCard(RecordId(id),
            date?.let { EventTime(LocalDate.parse(it)) }, ContentPreview(emptyList(), 0, 0))
        val first = card("first", "2024-12-31")
        val last = card("last", "2025-02-28")
        val unknown = card("unknown", null)
        val cards = listOf(last, unknown, card("later", "2025-03-01"), first, card("earlier", "2024-12-30"))
        val range = requireNotNull(EventDateFilter.Range.parse("2024-12-31", "2025-02"))
        assertEquals(EventDateMatches(listOf(last, first), 1), matchEventDates(cards, range))
        assertEquals(listOf(unknown), matchEventDates(cards, EventDateFilter.Undated).records)
        assertEquals(cards, matchEventDates(cards, null).records)
    }
}
