package com.baconaa.folio.records

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecentExperienceTest {
    private fun time(date: String, clock: String? = null, zone: String = "Asia/Shanghai") =
        EventTime(LocalDate.parse(date), clock?.let(LocalTime::parse), ZoneId.of(zone))

    @Test fun datesAreRangesAndUnknownEventsCannotBeOrdered() {
        val day = time("2026-11-17")
        assertEquals(RecentExperience.Empty, recentExperience(emptyList()))
        assertEquals(RecentExperience.Known(day), recentExperience(listOf(day)))
        assertEquals(RecentExperience.Known(day), recentExperience(listOf(day, time("2021-01-01", "18:30"))))
        assertEquals(RecentExperience.Uncertain(0), recentExperience(listOf(day, time("2026-11-17", "18:30"))))
        assertEquals(RecentExperience.Uncertain(1), recentExperience(listOf(day, null)))
        assertEquals(RecentExperience.Uncertain(2), recentExperience(listOf(null, null)))
    }

    @Test fun comparesActualInstantsButPreservesOriginalTimeAndDayPrecision() {
        val tokyo = time("2026-11-18", "00:30", "Asia/Tokyo")
        val losAngeles = time("2026-11-17", "09:00", "America/Los_Angeles")
        assertEquals(RecentExperience.Known(losAngeles), recentExperience(listOf(tokyo, losAngeles)))
        assertEquals(RecentExperience.Uncertain(0), recentExperience(listOf(time("2026-11-18", zone = "Asia/Tokyo"), losAngeles)))
        assertEquals(RecentExperience.Known(tokyo), recentExperience(listOf(tokyo, tokyo)))
        assertEquals(RecentExperience.Uncertain(0), recentExperience(listOf(time("2026-03-08", "02:30", "America/New_York"))))
        val repeated = time("2026-11-01", "01:30", "America/New_York")
        assertEquals(RecentExperience.Uncertain(0), recentExperience(listOf(repeated, time("2026-11-01", "06:00", "UTC"))))
    }

    @Test fun coordinatesRejectNonFiniteAndOutOfRangeValues() {
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 91.0 to 0.0, 0.0 to -181.0)) {
            assertTrue(runCatching { PlaceCoordinates(lat, lon) }.isFailure)
        }
        assertEquals(-90.0, PlaceCoordinates(-90.0, 180.0).latitude, 0.0)
    }
}
