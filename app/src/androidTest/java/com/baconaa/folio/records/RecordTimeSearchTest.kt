package com.baconaa.folio.records

import android.graphics.Color
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RecordTimeSearchTest : PhotoStoreFixture() {
    private val firstDay = LocalDate.of(2025, 1, 1)
    private val lastDay = LocalDate.of(2025, 12, 31)
    private val in2025 = EventTimeFilter.Within(firstDay..lastDay)

    private suspend fun draft(text: String, time: EventTime?) =
        requireNotNull(store.saveDraft(null, TextEdit(text, 0), time))

    @Test
    fun inclusiveRangesSeparateCertainPossibleAndUndatedRecords() = runBlocking {
        suspend fun publish(time: EventTime?) = store.completeMixedDraft(draft("沿途见闻", time))
        val onFirstDay = publish(EventTime(firstDay))
        val onLastDay = publish(EventTime(lastDay))
        val autumn = publish(EventTime.Season(2025, NamedSeason.AUTUMN))
        val winter = publish(EventTime.Season(2025, NamedSeason.WINTER))
        val atBoundary = publish(EventTime.Range(firstDay.minusMonths(1), firstDay))
        val enclosing = publish(EventTime.Range(firstDay.minusYears(1), lastDay.plusYears(1)))
        publish(EventTime(lastDay.plusDays(1)))
        val unknown = publish(EventTime.Description("大学时"))
        val blank = publish(null)
        store.setPinned(onFirstDay, true)
        store.setPinned(winter, true)

        val query = RecordQuery(keyword = "沿途", time = in2025)
        val matches = store.queryRecords(query)
        assertEquals(listOf(onFirstDay, autumn, onLastDay), matches.certain.map { it.id })
        assertEquals(listOf(winter, enclosing, atBoundary), matches.possible.map { it.id })
        assertEquals(2, matches.undatedCount)

        val february = LocalDate.of(2026, 2, 28)
        val nextWinterDay = store.queryRecords(query.copy(time = EventTimeFilter.Within(february..february)))
        assertTrue(nextWinterDay.certain.isEmpty())
        assertEquals(listOf(winter, enclosing), nextWinterDay.possible.map { it.id })

        val undated = store.queryRecords(query.copy(time = EventTimeFilter.Undated))
        assertEquals(listOf(blank, unknown), undated.certain.map { it.id })
        assertTrue(undated.possible.isEmpty())
        assertEquals(0, undated.undatedCount)
        assertEquals(listOf(unknown), store.queryRecords("大学时").map { it.id })
        assertEquals(listOf(unknown), store.search("大学时").map { it.id })
        val unrestricted = store.queryRecords(query.copy(time = null))
        assertEquals(store.queryRecords("沿途"), unrestricted.certain)
        assertTrue(unrestricted.possible.isEmpty())
        assertEquals(0, unrestricted.undatedCount)
    }

    @Test
    fun otherConditionsAndPublicationStateApplyToUndatedCountsAndSearchResults() = runBlocking {
        val photo = importPhoto(Color.BLUE).id
        val initial = draft("桥头买花", EventTime.Year(2025))
        store.appendPhotos(initial, listOf(photo))
        val tag = store.createDraftTag(initial, "周末")
        val place = store.createDraftPlace(initial, PlaceInfo("河边"))
        val record = store.completeMixedDraft(initial)
        store.setStarred(record, true)
        val query = RecordQuery(keyword = "买花", tagIds = setOf(tag), placeId = place, starredOnly = true,
            mediaTypes = setOf(MediaFilter.PHOTO, MediaFilter.AUDIO), time = in2025)

        val elsewhere = draft("桥头买花", null)
        store.appendPhotos(elsewhere, listOf(photo))
        store.saveDraftTags(elsewhere, listOf(tag))
        store.createDraftPlace(elsewhere, PlaceInfo("河边"))
        store.setStarred(store.completeMixedDraft(elsewhere), true)

        val edit = store.editRecord(record)
        store.saveDraftEventTime(edit, EventTime.Description("搬家前"))
        assertTrue(store.queryRecords("搬家前").isEmpty())
        val before = store.queryRecords(query)
        assertEquals(listOf(record), before.certain.map { it.id })
        assertEquals(0, before.undatedCount)

        store.completeMixedDraft(edit)
        val after = store.queryRecords(query)
        assertTrue(after.certain.isEmpty() && after.possible.isEmpty())
        assertEquals(1, after.undatedCount)
        val undated = query.copy(time = EventTimeFilter.Undated)
        assertEquals(listOf(record), store.queryRecords(undated).certain.map { it.id })
        assertEquals(0, store.queryRecords(query.copy(mediaTypes = setOf(MediaFilter.AUDIO))).undatedCount)

        store.moveToTrash(record)
        assertEquals(0, store.queryRecords(query).undatedCount)
        assertTrue(store.queryRecords(undated).certain.isEmpty())
        store.restoreRecord(record)
        assertEquals(1, store.queryRecords(query).undatedCount)
        assertEquals(listOf(record), store.queryRecords(undated).certain.map { it.id })
    }
}
