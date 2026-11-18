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

    @Test fun convertsSelectionToBackendCondition() {
        val range = EventDateFilter.Range.parse("2025", "2026-02").forQuery()
        assertEquals(EventTimeFilter.Within(LocalDate.parse("2025-01-01")..LocalDate.parse("2026-02-28")), range)
        assertEquals(EventTimeFilter.Undated, EventDateFilter.Undated.forQuery())
        assertNull(null.forQuery())
    }
}
