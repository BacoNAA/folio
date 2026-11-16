package com.baconaa.folio.records

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecordBrowsingTest : PhotoStoreFixture() {
    private var now = 1_000L
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Before
    fun useTestClock() {
        store = LocalTextStore(
            database, LocalPhotoOriginals.get(InstrumentationRegistry.getInstrumentation().targetContext),
            audioOriginals = LocalAudioOriginals.get(InstrumentationRegistry.getInstrumentation().targetContext),
            now = { now },
        )
    }

    private fun reopen() {
        reopenDatabase()
        useTestClock()
    }

    private suspend fun publish(text: String, firstRecordedAt: Long, event: EventTime?): RecordId {
        now = firstRecordedAt
        val edit = TextEdit(text, text.length)
        return store.completeDraft(requireNotNull(store.saveDraft(null, edit, event)), edit)
    }

    @Test
    fun eventTimelineUsesLocalDatesWhileRecentUsesFirstSaveAndStableInsertionOrder() = runBlocking {
        val february = publish("二月", 50L, EventTime(LocalDate.of(2026, 2, 1), zone = tokyo))
        val sameDay = LocalDate.of(2026, 1, 1)
        val lateClock = publish("当天深夜", 100L, EventTime(sameDay, LocalTime.of(23, 30), tokyo))
        val dateOnly = publish("只有日期", 200L, EventTime(sameDay, zone = tokyo))
        val tokyoNewYear = EventTime(sameDay, LocalTime.of(0, 30), tokyo)
        val earlyClock = publish("东京新年", 200L, tokyoNewYear)
        val losAngelesYearEnd = EventTime(
            LocalDate.of(2025, 12, 31), LocalTime.of(23, 30), ZoneId.of("America/Los_Angeles"),
        )
        val previousYear = publish("洛杉矶年末", 300L, losAngelesYearEnd)
        val backdated = publish("补记旧事", 400L, EventTime(LocalDate.of(2024, 7, 9), zone = tokyo))
        val unknown = publish("时间未定", 500L, null)

        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            reopen()
            val recent = store.listRecentRecords()
            assertEquals(
                listOf(unknown, backdated, previousYear, earlyClock, dateOnly, lateClock, february),
                recent.map { it.id },
            )
            val timeline = store.listEventTimeline()
            assertEquals(listOf(2026, 2025, 2024), timeline.years.map { it.year })
            assertEquals(
                listOf(february, earlyClock, dateOnly, lateClock),
                timeline.years[0].records.map { it.id },
            )
            assertEquals(listOf(previousYear), timeline.years[1].records.map { it.id })
            assertEquals(listOf(backdated), timeline.years[2].records.map { it.id })
            assertEquals(listOf(recent.first()), timeline.undated)
            assertEquals(tokyoNewYear, timeline.years[0].records[1].eventTime)
            assertEquals(losAngelesYearEnd, timeline.years[1].records.single().eventTime)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun queriesShowOnlyPublishedContentAndPreserveOrderThroughCompletionTrashAndRestore() = runBlocking {
        assertTrue(store.listRecentRecords().isEmpty())
        assertEquals(EventTimeline(emptyList(), emptyList()), store.listEventTimeline())
        val original = TextEdit("正式旧文", 4)
        val originalEvent = EventTime(LocalDate.of(2024, 7, 9), zone = tokyo)
        val initialDraft = requireNotNull(store.saveDraft(null, original, originalEvent))
        val newer = publish("后来记录", 2_000L, null)
        now = 3_000L
        store.saveDraft(null, TextEdit("尚未发布", 4), EventTime(LocalDate.of(2030, 1, 1), zone = tokyo))
        assertEquals(listOf(newer), store.listRecentRecords().map { it.id })
        assertTrue(store.listEventTimeline().years.isEmpty())

        now = 4_000L
        val record = store.completeDraft(initialDraft, original)
        val beforeRecent = store.listRecentRecords()
        val beforeTimeline = store.listEventTimeline()
        assertEquals(listOf(newer, record), beforeRecent.map { it.id })
        assertEquals(listOf(2024), beforeTimeline.years.map { it.year })
        assertEquals(listOf(record), beforeTimeline.years.single().records.map { it.id })
        assertEquals(listOf(newer), beforeTimeline.undated.map { it.id })

        now = 5_000L
        val edit = store.editRecord(record)
        val changedTime = EventTime(LocalDate.of(2028, 3, 4), zone = tokyo)
        store.saveDraft(edit, TextEdit("未完成修改", 5))
        store.saveDraftEventTime(edit, changedTime)
        assertEquals(beforeRecent, store.listRecentRecords())
        assertEquals(beforeTimeline, store.listEventTimeline())

        now = 6_000L
        assertEquals(record, store.completeDraft(edit, TextEdit("修改已完成", 5)))
        val afterRecent = store.listRecentRecords()
        val afterTimeline = store.listEventTimeline()
        assertEquals(listOf(newer, record), afterRecent.map { it.id })
        val published = afterRecent.last()
        val textId = beforeRecent.last().preview.items.single().id
        assertEquals(ContentPreview(listOf(PreviewContentItem.Text(textId, "修改已完成")), 0, 0), published.preview)
        assertEquals(changedTime, published.eventTime)
        assertEquals(listOf(EventYear(2028, listOf(published))), afterTimeline.years)
        assertEquals(beforeTimeline.undated, afterTimeline.undated)

        now = 7_000L
        assertTrue(store.moveToTrash(record))
        reopen()
        assertEquals(listOf(afterRecent.first()), store.listRecentRecords())
        assertEquals(EventTimeline(emptyList(), beforeTimeline.undated), store.listEventTimeline())
        now = 8_000L
        assertTrue(store.restoreRecord(record))
        assertEquals(afterRecent, store.listRecentRecords())
        assertEquals(afterTimeline, store.listEventTimeline())
    }

    @Test
    fun mixedPreviewKeepsItemOrderAndUnicodeBoundariesWhileCountingRepeatedPhotosByItem() = runBlocking {
        val photo = importPhoto(Color.BLUE)
        val draft = requireNotNull(store.saveDraft(null, TextEdit("占位", 2)))
        val blank = store.openMixedDraft(draft)!!.items.single().id
        store.editTextItem(draft, blank, TextEdit(" \n\t", 0))
        val captionPrefix = "图".repeat(79) + "🌉"
        val fullCaption = captionPrefix + "照片说明末尾"
        val firstPhoto = store.insertPhoto(draft, null, photo.id, fullCaption)
        val textPrefix = "文".repeat(79) + "🌸"
        val fullText = textPrefix + "完整正文末尾"
        val text = store.insertText(draft, null, TextEdit(fullText, fullText.length))
        val secondPhoto = store.insertPhoto(draft, null, photo.id, "同一照片的另一处说明")
        store.insertText(draft, null, TextEdit("\n\u3000\t", 0))
        store.insertText(draft, null, TextEdit("未展示的正文", 6))
        store.insertPhoto(draft, null, photo.id)
        store.insertPhoto(draft, null, photo.id, "最后一张照片")
        val fullItems = store.openMixedDraft(draft)!!.items
        val record = store.completeMixedDraft(draft)

        reopen()
        val card = store.listRecentRecords().single()
        assertEquals(record, card.id)
        assertEquals(
            ContentPreview(
                listOf(
                    PreviewContentItem.Photo(firstPhoto, photo.id, captionPrefix),
                    PreviewContentItem.Text(text, textPrefix),
                    PreviewContentItem.Photo(secondPhoto, photo.id, "同一照片的另一处说明"),
                ),
                remainingItemCount = 3,
                remainingPhotoCount = 2,
            ),
            card.preview,
        )
        assertEquals(listOf(card), store.listEventTimeline().undated)
        val fullContent = requireNotNull(store.openRecordContent(record))
        assertEquals(fullItems, fullContent.items)
        assertPhotos(fullContent.photos, photo, photo, photo, photo)
    }
}
