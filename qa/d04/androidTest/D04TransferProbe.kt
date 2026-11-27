package com.baconaa.folio.records

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalAudioPlayback
import com.baconaa.folio.media.LocalPhotoOriginals
import java.io.InputStream
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Run each phase only on its specified disposable installation. The host transfers the user-created archive. */
class D04TransferProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = LocalTextStore.get(context)
    private val alpha = RecordGroupId("D04-A")
    private val chapter = RecordGroupId("D04-C")
    private val beta = RecordGroupId("D04-B")
    private val winter = EventTime.Season(2025, NamedSeason.WINTER, zone = ZoneId.of("Asia/Shanghai"))

    private suspend fun hash(stream: InputStream) = stream.use { digestStream(it) }.joinToString("") { "%02x".format(it) }

    @Test fun prepareUpgradedSource() = runBlocking {
        val seed = context.getSharedPreferences("m01-upgrade-probe", 0)
        val trip = RecordId(requireNotNull(seed.getString("trip", null)))
        val content = store.openRecordContent(trip)!!
        assertEquals(seed.getString("photoSha", null), hash(LocalPhotoOriginals.get(context).openOriginal(content.photos.single())))
        val audio = content.items.filterIsInstance<ContentItem.Audio>().single()
        assertEquals(seed.getString("audioSha", null), hash(LocalAudioOriginals.get(context).openOriginal(audio.audioId)))
        assertEquals(seed.getLong("first", -1), content.times.firstRecordedAt)
        assertTrue(content.marks.isStarred)
        suspend fun at(text: String, street: String): RecordId {
            val draft = store.saveDraft(null, TextEdit(text, 0), winter)!!
            store.createDraftPlace(draft, PlaceInfo("面馆", address = street))
            if (text == "维修") store.appendPhotos(draft, listOf(content.photos.single()))
            return store.completeMixedDraft(draft)
        }
        val repair = at("维修", "东街")
        val ticket = at("购票", "西街")
        val a = listOf(trip, repair)
        val b = listOf(ticket, trip)
        assertEquals(GroupUpdate.Saved(alpha, a), store.createGroup(alpha, "甲组", a))
        assertEquals(GroupUpdate.Saved(beta, b), store.createGroup(beta, "乙组", b))
        val cover = ChapterCover(trip, content.items.filterIsInstance<ContentItem.Photo>().single().id)
        store.saveChapter(chapter, null, ChapterProfile("冬日走访", "2025-12", "2026", "两条街的面馆", cover))
        store.appendToGroup(chapter, listOf(ticket, trip, repair))
        store.changeRelation(trip, null, repair)
        val source = store.openRecordContent(repair)!!
        store.saveObservation(TrackOwner(source.place!!), repair, source.items.filterIsInstance<ContentItem.Photo>().single().id, "更换门面招牌")
        store.setSeasonExcluded(ticket, true)
        store.saveSeasonMonths(mapOf(NamedSeason.WINTER to SeasonMonths(11, 1)))
        store.reading.saveEnlargedBody(true)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("photoSha", seed.getString("photoSha", null))
            putString("audioSha", seed.getString("audioSha", null))
            putString("trip", trip.value)
        })
    }

    @Test fun confirmCleanDestination() = runBlocking {
        assertTrue(store.listRecentRecords().isEmpty())
        assertTrue(store.listDrafts().isEmpty())
        assertTrue(store.listTrash().isEmpty())
        assertTrue(store.listGroups().isEmpty())
        assertNull(context.getSharedPreferences("m01-upgrade-probe", 0).getString("trip", null))
    }

    @Test fun verifyAfterRestore() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val trip = RecordId(requireNotNull(args.getString("trip")))
        val repair = store.search("维修").single().id
        val ticket = store.search("购票").single().id
        assertEquals(listOf(trip, repair), store.openGroup(alpha)!!.members.map { it.id })
        assertEquals(listOf(ticket, trip), store.openGroup(beta)!!.members.map { it.id })
        val rooms = listOf(repair, ticket).map { store.openRecordContent(it)!! }
        assertEquals(listOf("东街", "西街"), rooms.map { it.place!!.info.address })
        assertNotEquals(rooms[0].place!!.id, rooms[1].place!!.id)
        assertTrue(rooms.all { it.times.eventTime == winter })
        assertEquals(SeasonMonths(11, 1), store.readSeasonMonths().getValue(NamedSeason.WINTER))
        assertEquals(true, store.reading.readEnlargedBody())
        val content = store.openRecordContent(trip)!!
        assertTrue(content.marks.isStarred)
        val chapterData = store.openGroup(chapter)!!
        val cover = ChapterCover(trip, content.items.filterIsInstance<ContentItem.Photo>().single().id)
        assertEquals(ChapterProfile("冬日走访", "2025-12", "2026", "两条街的面馆", cover), chapterData.chapter)
        assertEquals(content.photos.single(), chapterData.cover!!.photo)
        assertEquals(listOf(ticket, trip, repair), chapterData.members.map { it.id })
        assertEquals(setOf(repair), store.relationChoices(trip).related)
        assertEquals(setOf(trip), store.relationChoices(repair).related)
        val node = store.readObservations(TrackOwner(rooms[0].place!!)).single()
        assertEquals("更换门面招牌", node.note)
        assertEquals(repair, node.record)
        assertEquals(rooms[0].items.filterIsInstance<ContentItem.Photo>().single(), node.item)
        val reviews = store.reviewDates(DateReviewQuery(java.time.LocalDate.of(2026, 11, 26), NamedSeason.WINTER))
        assertTrue(reviews.single { it.record.id == ticket }.excluded)
        assertFalse(reviews.single { it.record.id == repair }.excluded)
        val sound = content.items.filterIsInstance<ContentItem.Audio>().single().audioId
        assertEquals(args.getString("photoSha"), hash(LocalPhotoOriginals.get(context).openOriginal(content.photos.single())))
        assertEquals(args.getString("audioSha"), hash(LocalAudioOriginals.get(context).openOriginal(sound)))
        lateinit var playback: LocalAudioPlayback
        instrumentation.runOnMainSync { playback = LocalAudioPlayback.get(context); playback.play(sound) }
        try {
            val end = withTimeout(12_000) { playback.state.first { it.hasEnded || it.error != null } }
            assertTrue(end.hasEnded)
            assertNull(end.error)
        } finally { instrumentation.runOnMainSync { playback.release() } }
        val edits = store.listDrafts()
        assertEquals(2, edits.size)
        assertTrue(edits.any { store.openDraft(it.id)?.text == "明天带伞" })
        assertEquals(1, store.listTrash().size)
        assertTrue(store.openTrashRecord(store.listTrash().single().id)!!.hasDraft)
        val edit = store.editRecord(store.openGroup(alpha)!!.members.first().id)
        assertEquals("秋游\n补记山路", store.openDraft(edit)!!.text)
        store.insertText(edit, null, TextEdit("换机后续写", 5))
        assertEquals(trip, store.completeMixedDraft(edit))
        val shared = store.openGroup(beta)!!.members.last().id
        assertTrue(store.openRecord(shared)!!.contains("换机后续写"))
    }
}
