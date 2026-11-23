package com.baconaa.folio.records

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.media.createAudioTestClip
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "text-migration-${UUID.randomUUID()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun oldDraftAndPublishedRecordSurviveUpgradeAndRemainEditable() = runBlocking {
        createDatabase(1) { sql ->
            sql.execSQL("INSERT INTO text_drafts VALUES ('unfinished', '等雨停', 1, 300, 400)")
            sql.execSQL("INSERT INTO text_records VALUES ('published', 'submitted', '旧桥', 100, 200, NULL)")
        }

        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val record = RecordId("published")
            val oldDraft = DraftId("unfinished")
            assertEquals(TextEdit("等雨停", 1), store.openDraft(oldDraft))
            assertEquals(record, store.search("旧桥").single().id)
            val stored = requireNotNull(database.textDao().activeRecord(record.value))
            assertEquals(100L, stored.firstRecordedAt)
            assertEquals(200L, stored.modifiedAt)
            assertEquals(record, store.completeDraft(DraftId("submitted"), TextEdit("旧桥", 2)))

            val edit = store.editRecord(record)
            store.saveDraft(edit, TextEdit("新桥", 1))
            assertEquals(TextEdit("新桥", 1), store.openDraft(edit))
            assertEquals("旧桥", store.openRecord(record))
            store.discardDraft(edit)
            assertEquals(listOf(oldDraft), store.listDrafts().map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun versionTwoEditKeepsItsParentAndCursorWhenPhotosAreIntroduced() = runBlocking {
        createDatabase(2) { sql ->
            sql.execSQL("INSERT INTO text_records VALUES ('record', 'source', '原文', 100, 200, NULL, 3)")
            sql.execSQL("INSERT INTO text_drafts VALUES ('edit', '编辑中', 1, 100, 300)")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit', 'record', 3)")
        }

        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val record = RecordId("record")
            val draft = DraftId("edit")
            assertEquals(draft, store.editRecord(record))
            assertEquals(TextEdit("编辑中", 1), store.openDraft(draft))
            assertEquals(emptyList<PhotoId>(), store.openDraftPhotos(draft))
            assertEquals("原文", store.openRecord(record))
            assertEquals(record, store.completeDraft(draft, TextEdit("新正文", 3)))
            assertEquals("新正文", store.openRecord(record))
        } finally {
            database.close()
        }
    }

    @Test
    fun versionThreePhotosKeepTheirItemIdsAndTheEditKeepsItsCursor() = runBlocking {
        val photo = PhotoId(UUID.randomUUID())
        val first = ItemId(UUID.randomUUID().toString())
        val second = ItemId(UUID.randomUUID().toString())
        createDatabase(3) { sql ->
            sql.execSQL("INSERT INTO text_records VALUES ('record', 'source', '旧桥', 100, 200, NULL, 3)")
            sql.execSQL("INSERT INTO text_drafts VALUES ('edit', '新🌉桥', 3, 100, 300)")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit', 'record', 3)")
            for ((item, position) in listOf(second to 1, first to 0)) {
                val values = arrayOf<Any>(item.value, photo.value.toString(), position)
                sql.execSQL("INSERT INTO record_photos (recordId,itemId,photoId,position) VALUES ('record',?,?,?)", values)
                sql.execSQL("INSERT INTO draft_photos (draftId,itemId,photoId,position) VALUES ('edit',?,?,?)", values)
            }
        }

        val db = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(db, context)
            val record = RecordId("record")
            val content = requireNotNull(store.openRecordContent(record))
            val textId = content.items.first().id
            val photos = listOf(ContentItem.Photo(first, photo), ContentItem.Photo(second, photo))
            assertEquals(listOf(ContentItem.Text(textId, "旧桥")) + photos, content.items)

            val draft = store.editRecord(record)
            assertEquals(DraftId("edit"), draft)
            val resumed = requireNotNull(store.openMixedDraft(draft))
            assertEquals(listOf(ContentItem.Text(textId, "新🌉桥")) + photos, resumed.items)
            assertEquals(textId, resumed.activeItemId)
            assertEquals(3, resumed.cursor)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionFiveTrashKeepsMixedDraftAndRemovalWhenCleanupQueueIsIntroduced() = runBlocking {
        val oldPhoto = PhotoId(UUID.randomUUID())
        val draftPhoto = PhotoId(UUID.randomUUID())
        val removedPhoto = PhotoId(UUID.randomUUID())
        val text = ItemId("text")
        val first = ItemId("first-photo")
        val second = ItemId("second-photo")
        val removed = ItemId("removed-photo")
        createDatabase(5) { sql ->
            sql.execSQL("INSERT INTO text_records VALUES ('record', 'source', '旧桥', 100, 200, 400, 3)")
            sql.execSQL("INSERT INTO text_drafts VALUES ('edit', '新🌉桥', 3, 100, 300, 'text')")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit', 'record', 3)")
            sql.execSQL("INSERT INTO record_items VALUES ('record', 0, 'text', '旧桥', NULL, '')")
            sql.execSQL("INSERT INTO record_items VALUES ('record', 1, 'first-photo', NULL, ?, '原图说明')",
                arrayOf(oldPhoto.value.toString()))
            sql.execSQL("INSERT INTO draft_items VALUES ('edit', 0, 'text', '新🌉桥', NULL, '')")
            sql.execSQL("INSERT INTO draft_items VALUES ('edit', 1, 'second-photo', NULL, ?, '草稿照片')",
                arrayOf(draftPhoto.value.toString()))
            sql.execSQL("""INSERT INTO removed_draft_items
                (draftId, position, previousItemId, nextItemId, cursor, itemId, text, photoId, caption)
                VALUES ('edit', 1, 'text', 'second-photo', 0, 'removed-photo', NULL, ?, '可撤销照片')""",
                arrayOf(removedPhoto.value.toString()))
        }

        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val record = RecordId("record")
            val draft = DraftId("edit")
            val publishedItems = listOf(ContentItem.Text(text, "旧桥"), ContentItem.Photo(first, oldPhoto, "原图说明"))
            val draftItems = listOf(ContentItem.Text(text, "新🌉桥"), ContentItem.Photo(second, draftPhoto, "草稿照片"))
            val trashed = requireNotNull(store.openTrashRecord(record))
            assertEquals(publishedItems, trashed.content.items)
            assertEquals(400L, trashed.deletedAt)
            assertTrue(trashed.hasDraft)
            assertEquals(listOf(record), store.listTrash().map { it.id })
            assertNull(store.openRecordContent(record))
            assertNull(store.openMixedDraft(draft))
            assertTrue(store.listDrafts().isEmpty())
            assertFalse(store.canUndoRemoval(draft))

            assertTrue(store.restoreRecord(record))
            assertEquals(publishedItems, store.openRecordContent(record)!!.items)
            assertEquals(MixedDraft(draftItems, text, 3), store.openMixedDraft(draft))
            assertEquals(draft, store.editRecord(record))
            val restored = requireNotNull(database.textDao().activeRecord(record.value))
            assertEquals(100L, restored.firstRecordedAt)
            assertEquals(200L, restored.modifiedAt)
            assertEquals(3L, restored.revision)
            assertTrue(store.canUndoRemoval(draft))
            assertTrue(store.undoRemoval(draft))
            val restoredItems = listOf(draftItems[0], ContentItem.Photo(removed, removedPhoto, "可撤销照片"), draftItems[1])
            assertEquals(MixedDraft(restoredItems, removed, 0), store.openMixedDraft(draft))
            assertEquals(publishedItems, store.openRecordContent(record)!!.items)
            assertFalse(store.canUndoRemoval(draft))
        } finally {
            database.close()
        }
    }

    @Test
    fun versionSixKeepsExistingTimesContentAndCleanupQueueWithUnknownEventTime() = runBlocking {
        val pendingPhoto = PhotoId(UUID.randomUUID())
        createDatabase(6) { sql ->
            sql.execSQL("INSERT INTO text_records VALUES ('record', 'source', '旧桥', 100, 200, NULL, 3)")
            sql.execSQL("INSERT INTO text_drafts VALUES ('edit', '新桥', 1, 100, 300, 'text')")
            sql.execSQL("INSERT INTO text_drafts VALUES ('unfinished', '等雨停', 2, 400, 500, 'unfinished-text')")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit', 'record', 3)")
            sql.execSQL("INSERT INTO record_items VALUES ('record', 0, 'text', '旧桥', NULL, '')")
            sql.execSQL("INSERT INTO draft_items VALUES ('edit', 0, 'text', '新桥', NULL, '')")
            sql.execSQL("INSERT INTO draft_items VALUES ('unfinished', 0, 'unfinished-text', '等雨停', NULL, '')")
            sql.execSQL("INSERT INTO photo_cleanup_queue VALUES (?)", arrayOf(pendingPhoto.value.toString()))
        }

        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context) { 600L }
            val record = RecordId("record")
            val draft = DraftId("edit")
            val unfinished = DraftId("unfinished")
            val published = requireNotNull(store.openRecordContent(record))
            assertEquals(listOf(ContentItem.Text(ItemId("text"), "旧桥")), published.items)
            assertEquals(RecordTimes(100L, 200L, null), published.times)
            assertEquals(DraftTimes(100L, null), store.openDraftTimes(draft))
            assertEquals(DraftTimes(400L, null), store.openDraftTimes(unfinished))
            assertEquals(TextEdit("新桥", 1), store.openDraft(draft))
            assertEquals(TextEdit("等雨停", 2), store.openDraft(unfinished))
            assertEquals(draft, store.editRecord(record))
            assertEquals(3L, database.textDao().activeRecord(record.value)!!.revision)
            assertEquals(listOf(pendingPhoto.value.toString()), database.photoCleanupDao().candidates())

            val event = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Asia/Shanghai"))
            store.saveDraftEventTime(draft, event)
            assertEquals(DraftTimes(100L, event), store.openDraftTimes(draft))
            assertEquals(published, store.openRecordContent(record))
            assertEquals(record, store.completeMixedDraft(draft))
            assertEquals("新桥", store.openRecord(record))
            assertEquals(RecordTimes(100L, 600L, event), store.openRecordContent(record)!!.times)
        } finally {
            database.close()
        }
    }

    @Test
    fun versionSevenKeepsOrderedContentTimeAndUndoWhileAddingChecklistStorage() = runBlocking {
        val photo = PhotoId(UUID.randomUUID())
        val event = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Asia/Shanghai"))
        val date = event.date.toEpochDay()
        createDatabase(7) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,revision,eventDate,eventZone)
                VALUES ('record','source','旧桥',100,200,3,?,'Asia/Shanghai')""", arrayOf(date))
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,activeItemId,eventDate,eventZone)
                VALUES ('edit','新🌉桥',3,100,300,'text',?,'Asia/Shanghai')""", arrayOf(date))
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',3)")
            sql.execSQL("INSERT INTO record_items VALUES ('record',0,'text','旧桥',NULL,'')")
            sql.execSQL("INSERT INTO draft_items VALUES ('edit',0,'text','新🌉桥',NULL,'')")
            sql.execSQL("INSERT INTO record_items VALUES ('record',1,'photo',NULL,?,'原图说明')",
                arrayOf(photo.value.toString()))
            sql.execSQL("INSERT INTO draft_items VALUES ('edit',1,'photo',NULL,?,'草稿说明')",
                arrayOf(photo.value.toString()))
            sql.execSQL("""INSERT INTO removed_draft_items
                (draftId,position,previousItemId,nextItemId,cursor,itemId,text,photoId,caption)
                VALUES ('edit',1,'text','photo',1,'removed','雨',NULL,'')""")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context) { 600L }
            val record = RecordId("record")
            val draft = DraftId("edit")
            assertEquals(RecordTimes(100L, 200L, event), store.openRecordContent(record)!!.times)
            assertEquals(DraftTimes(100L, event), store.openDraftTimes(draft))
            assertEquals(3, store.openMixedDraft(draft)!!.cursor)
            assertTrue(database.itemDao().recordItems(record.value).all { it.checklistJson == null })
            assertTrue(database.itemDao().draftItems(draft.value).all { it.checklistJson == null })
            assertNull(database.removedItemDao().get(draft.value)!!.slot.checklistJson)
            assertEquals(listOf(PreviewContentItem.Text(ItemId("text"), "旧桥"),
                PreviewContentItem.Photo(ItemId("photo"), photo, "原图说明")),
                store.listRecentRecords().single().preview.items)
            assertEquals(record, store.search("旧桥").single().id)
            assertTrue(store.undoRemoval(draft))
            assertEquals(listOf(ContentItem.Text(ItemId("text"), "新🌉桥"),
                ContentItem.Text(ItemId("removed"), "雨"), ContentItem.Photo(ItemId("photo"), photo, "草稿说明")),
                store.openMixedDraft(draft)!!.items)
            val entries = listOf(ChecklistEntry(ChecklistEntryId("new-entry"), "新清单", true))
            val checklist = store.insertChecklist(draft, ItemId("text"), entries)
            assertEquals(ContentItem.Checklist(checklist, entries), store.openMixedDraft(draft)!!.items[1])
            assertTrue(store.search("新清单").isEmpty())
            assertEquals(record, store.completeMixedDraft(DraftId("source")))
        } finally {
            database.close()
        }
    }

    @Test
    fun versionEightKeepsChecklistDraftTrashUndoAndPhotoCleanupWhenAudioIsIntroduced() = runBlocking {
        val pendingPhoto = PhotoId(UUID.randomUUID())
        val oldEntries = listOf(ChecklistEntry(ChecklistEntryId("tent"), "帐篷", false))
        val changedEntries = listOf(ChecklistEntry(ChecklistEntryId("tent"), "帐篷", true))
        val removedEntries = listOf(ChecklistEntry(ChecklistEntryId("water"), "带水", true))
        createDatabase(8) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,deletedAt,revision)
                VALUES ('record','source','帐篷',100,200,400,3)""")
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,activeItemId)
                VALUES ('edit','帐篷',0,100,300,'checklist')""")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',3)")
            sql.execSQL("""INSERT INTO record_items
                (recordId,position,itemId,checklistJson) VALUES ('record',0,'checklist',?)""",
                arrayOf("""[{"id":"tent","text":"帐篷","checked":false}]"""))
            sql.execSQL("""INSERT INTO draft_items
                (draftId,position,itemId,checklistJson) VALUES ('edit',0,'checklist',?)""",
                arrayOf("""[{"id":"tent","text":"帐篷","checked":true}]"""))
            sql.execSQL("""INSERT INTO removed_draft_items
                (draftId,position,previousItemId,nextItemId,cursor,itemId,checklistJson)
                VALUES ('edit',1,'checklist',NULL,0,'removed',?)""",
                arrayOf("""[{"id":"water","text":"带水","checked":true}]"""))
            sql.execSQL("INSERT INTO photo_cleanup_queue VALUES (?)", arrayOf(pendingPhoto.value.toString()))
        }
        val audio = createAudioTestClip(context)
        val originals = LocalAudioOriginals.get(context)
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val record = RecordId("record")
            val draft = DraftId("edit")
            val published = listOf(ContentItem.Checklist(ItemId("checklist"), oldEntries))
            assertEquals(published, store.openTrashRecord(record)!!.content.items)
            assertEquals(RecordTimes(100L, 200L, null), store.openTrashRecord(record)!!.content.times)
            assertTrue(store.openTrashRecord(record)!!.hasDraft)
            assertNull(store.openMixedDraft(draft))
            assertTrue(database.itemDao().recordItems(record.value).all { it.audioId == null && it.audioDurationMillis == null })
            assertNull(database.removedItemDao().get(draft.value)!!.slot.audioId)
            assertTrue(database.audioCleanupDao().candidates().isEmpty())
            assertEquals(listOf(pendingPhoto.value.toString()), database.photoCleanupDao().candidates())
            assertTrue(store.restoreRecord(record))
            assertEquals(draft, store.editRecord(record))
            assertEquals(listOf(ContentItem.Checklist(ItemId("checklist"), changedEntries)), store.openMixedDraft(draft)!!.items)
            assertNull(store.listDrafts().single().totalAudioDurationMillis)
            assertTrue(store.undoRemoval(draft))
            assertEquals(ContentItem.Checklist(ItemId("removed"), removedEntries), store.openMixedDraft(draft)!!.items.last())
            store.appendAudio(draft, audio.id)
            val saved = store.openMixedDraft(draft)!!.items
            assertEquals(published, store.openRecordContent(record)!!.items)
            assertEquals(record, store.completeMixedDraft(draft))
            assertEquals(saved, store.openRecordContent(record)!!.items)
        } finally {
            database.close()
            originals.deleteOriginal(audio.id)
        }
    }

    @Test
    fun versionNineKeepsAudioChecklistAndUndoWhileDefaultingRecordMarksToFalse() = runBlocking {
        val audio = createAudioTestClip(context)
        val originals = LocalAudioOriginals.get(context)
        val pendingAudio = UUID.randomUUID().toString()
        val event = EventTime(LocalDate.of(2021, 7, 9), zone = ZoneId.of("Asia/Shanghai"))
        createDatabase(9) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,revision,eventDate,eventZone)
                VALUES ('record','source','帐篷',100,200,3,?,'Asia/Shanghai')""",
                arrayOf(event.date.toEpochDay()))
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,deletedAt)
                VALUES ('trash','trash-source','旧记录',100,200,400)""")
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,activeItemId,eventDate,eventZone)
                VALUES ('edit','帐篷',0,100,300,'audio',?,'Asia/Shanghai')""",
                arrayOf(event.date.toEpochDay()))
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',3)")
            val checklist = """[{"id":"tent","text":"帐篷","checked":true}]"""
            sql.execSQL("""INSERT INTO record_items
                (recordId,position,itemId,checklistJson) VALUES ('record',0,'checklist',?)""", arrayOf(checklist))
            sql.execSQL("""INSERT INTO draft_items
                (draftId,position,itemId,checklistJson) VALUES ('edit',0,'checklist',?)""", arrayOf(checklist))
            sql.execSQL("""INSERT INTO record_items
                (recordId,position,itemId,caption,audioId,audioDurationMillis)
                VALUES ('record',1,'audio','帐篷里的雨',?,?)""", arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
            sql.execSQL("""INSERT INTO draft_items
                (draftId,position,itemId,caption,audioId,audioDurationMillis)
                VALUES ('edit',1,'audio','帐篷里的雨',?,?)""", arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
            sql.execSQL("""INSERT INTO removed_draft_items
                (draftId,position,previousItemId,cursor,itemId,caption,audioId,audioDurationMillis)
                VALUES ('edit',2,'audio',0,'removed','另一处声音',?,?)""", arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
            sql.execSQL("INSERT INTO audio_cleanup_queue VALUES (?)", arrayOf(pendingAudio))
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context) { 600L }
            val record = RecordId("record")
            val draft = DraftId("edit")
            val content = store.openRecordContent(record)!!
            assertEquals(RecordMarks(), content.marks)
            assertEquals(RecordTimes(100L, 200L, event), content.times)
            assertEquals(ContentItem.Checklist(ItemId("checklist"),
                listOf(ChecklistEntry(ChecklistEntryId("tent"), "帐篷", true))), content.items[0])
            assertEquals(ContentItem.Audio(ItemId("audio"), audio.id, audio.durationMillis, "帐篷里的雨"), content.items[1])
            assertEquals(content.items, store.openMixedDraft(draft)!!.items)
            assertEquals(RecordMarks(), store.openTrashRecord(RecordId("trash"))!!.content.marks)
            assertTrue(store.listStarredRecords().isEmpty())
            assertEquals(listOf(pendingAudio), database.audioCleanupDao().candidates())
            assertEquals(audio, originals.inspect(audio.id))
            store.setStarred(record, true)
            store.setPinned(record, true)
            assertEquals(3L, database.textDao().activeRecord(record.value)!!.revision)
            assertEquals(content.times, store.openRecordContent(record)!!.times)
            assertEquals(RecordMarks(true, true), store.listStarredRecords().single().marks)
            assertTrue(store.undoRemoval(draft))
            assertEquals(ContentItem.Audio(ItemId("removed"), audio.id, audio.durationMillis, "另一处声音"),
                store.openMixedDraft(draft)!!.items.last())
            assertEquals(record, store.completeMixedDraft(draft))
            assertEquals(RecordMarks(true, true), store.openRecordContent(record)!!.marks)
            assertEquals(3, store.openRecordContent(record)!!.items.size)
            assertEquals(audio, originals.inspect(audio.id))
        } finally {
            database.close()
            originals.deleteOriginal(audio.id)
        }
    }

    @Test
    fun versionTenKeepsMarkedTrashAndMixedDraftWhileAddingIndependentTagRelations() = runBlocking {
        val audio = createAudioTestClip(context)
        val originals = LocalAudioOriginals.get(context)
        val pendingPhoto = UUID.randomUUID().toString()
        val pendingAudio = UUID.randomUUID().toString()
        createDatabase(10) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,deletedAt,revision,isStarred,isPinned)
                VALUES ('record','source','帐篷',100,200,400,3,1,1)""")
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,activeItemId)
                VALUES ('edit','帐篷',0,100,300,'audio')""")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',3)")
            val checklist = """[{"id":"tent","text":"帐篷","checked":true}]"""
            sql.execSQL("""INSERT INTO record_items
                (recordId,position,itemId,checklistJson) VALUES ('record',0,'checklist',?)""", arrayOf(checklist))
            sql.execSQL("""INSERT INTO draft_items
                (draftId,position,itemId,checklistJson) VALUES ('edit',0,'checklist',?)""", arrayOf(checklist))
            sql.execSQL("""INSERT INTO record_items
                (recordId,position,itemId,caption,audioId,audioDurationMillis)
                VALUES ('record',1,'audio','帐篷里的雨',?,?)""", arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
            sql.execSQL("""INSERT INTO draft_items
                (draftId,position,itemId,caption,audioId,audioDurationMillis)
                VALUES ('edit',1,'audio','帐篷里的雨',?,?)""", arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
            sql.execSQL("""INSERT INTO removed_draft_items
                (draftId,position,previousItemId,cursor,itemId,text)
                VALUES ('edit',2,'audio',1,'removed','雨')""")
            sql.execSQL("INSERT INTO photo_cleanup_queue VALUES (?)", arrayOf(pendingPhoto))
            sql.execSQL("INSERT INTO audio_cleanup_queue VALUES (?)", arrayOf(pendingAudio))
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context) { 600L }
            val record = RecordId("record")
            val draft = DraftId("edit")
            val content = store.openTrashRecord(record)!!.content
            assertEquals(RecordMarks(true, true), content.marks)
            assertEquals(RecordTimes(100L, 200L, null), content.times)
            assertEquals(ContentItem.Audio(ItemId("audio"), audio.id, audio.durationMillis, "帐篷里的雨"), content.items.last())
            assertTrue(content.tags.isEmpty())
            assertTrue(store.listTags().isEmpty())
            assertNull(store.openDraftTags(draft))
            assertEquals(listOf(pendingPhoto), database.photoCleanupDao().candidates())
            assertEquals(listOf(pendingAudio), database.audioCleanupDao().candidates())
            assertTrue(store.restoreRecord(record))
            assertEquals(content.items, store.openMixedDraft(draft)!!.items)
            assertEquals(emptyList<Tag>(), store.openDraftTags(draft))
            assertTrue(store.undoRemoval(draft))
            assertEquals(ContentItem.Text(ItemId("removed"), "雨"), store.openMixedDraft(draft)!!.items.last())
            val tag = store.createDraftTag(draft, "露营")
            assertTrue(store.openRecordContent(record)!!.tags.isEmpty())
            assertEquals(record, store.completeMixedDraft(draft))
            assertEquals(listOf(Tag(tag, "露营")), store.queryRecords(tagIds = setOf(tag), starredOnly = true).single().tags)
            assertEquals(RecordMarks(true, true), store.openRecordContent(record)!!.marks)
            assertEquals(audio, originals.inspect(audio.id))
        } finally {
            database.close()
            originals.deleteOriginal(audio.id)
        }
    }

    @Test
    fun versionElevenTagsAndDraftRemainIntactWhenPlacesAreAdded() = runBlocking {
        createDatabase(11) { sql ->
            sql.execSQL("INSERT INTO text_drafts (id,body,cursor,firstRecordedAt,lastEditedAt) VALUES ('draft','旧草稿',2,100,200)")
            sql.execSQL("INSERT INTO draft_items (draftId,position,itemId,text) VALUES ('draft',0,'item','旧草稿')")
            sql.execSQL("INSERT INTO tags VALUES ('tag','旧标签','draft')")
            sql.execSQL("INSERT INTO draft_tags VALUES ('draft','tag',0)")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val draft = DraftId("draft")
            assertEquals(TextEdit("旧草稿", 2), store.openDraft(draft))
            assertEquals("旧标签", store.openDraftTags(draft)!!.single().name)
            assertNull(store.openDraftPlace(draft))
            val place = store.createDraftPlace(draft, PlaceInfo("新地点"))
            val id = store.completeMixedDraft(draft)
            assertEquals(place, store.openRecordContent(id)!!.place!!.id)
            assertEquals("旧标签", store.openRecordContent(id)!!.tags.single().name)
        } finally { database.close() }
    }

    @Test fun versionTwelvePlacesKeepIdentityAndStartWithoutCoordinates() = runBlocking {
        createDatabase(12) { sql ->
            sql.execSQL("INSERT INTO text_drafts (id,body,cursor,firstRecordedAt,lastEditedAt) VALUES ('draft','坐标升级',0,100,200)")
            sql.execSQL("INSERT INTO draft_items (draftId,position,itemId,text) VALUES ('draft',0,'item','坐标升级')")
            sql.execSQL("INSERT INTO places (id,name,city,address,note,ownerDraftId) VALUES ('private','私有','成都','西街','靠河','draft')")
            sql.execSQL("INSERT INTO places (id,name,city,address,note,ownerDraftId) VALUES ('shared','共享','','','',NULL)")
            sql.execSQL("INSERT INTO draft_places VALUES ('draft','private')")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val draft = DraftId("draft")
            val place = store.openDraftPlace(draft)!!
            assertEquals(PlaceId("private"), place.id)
            assertEquals(PlaceInfo("私有", "成都", "西街", "靠河"), place.info)
            assertNull(store.openPlace(PlaceId("shared"))!!.info.coordinates)
            assertEquals(RecentExperience.Empty, store.listPlaces().single().recentExperience)
            val point = PlaceCoordinates(30.0, 104.0)
            store.updateDraftPlace(draft, place.id, place.info.copy(coordinates = point))
            val record = store.completeMixedDraft(draft)
            assertEquals(point, store.openRecordContent(record)!!.place!!.info.coordinates)
        } finally { database.close() }
    }

    @Test fun versionThirteenCoordinatesAndPrivateDraftSurviveObjectMigration() = runBlocking {
        createDatabase(13) { sql ->
            sql.execSQL("INSERT INTO text_drafts (id,body,cursor,firstRecordedAt,lastEditedAt) VALUES ('draft','旧地点与标签',2,100,200)")
            sql.execSQL("INSERT INTO draft_items (draftId,position,itemId,text) VALUES ('draft',0,'item','旧地点与标签')")
            sql.execSQL("INSERT INTO places (id,name,city,address,note,ownerDraftId,latitude,longitude) VALUES ('place','河边','成都','','','draft',30.67,104.06)")
            sql.execSQL("INSERT INTO draft_places VALUES ('draft','place')")
            sql.execSQL("INSERT INTO tags VALUES ('tag','旧标签','draft')")
            sql.execSQL("INSERT INTO draft_tags VALUES ('draft','tag',0)")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val draft = DraftId("draft")
            assertTrue(store.listObjects().isEmpty())
            assertEquals(emptyList<RecordObject>(), store.openDraftObjects(draft))
            assertEquals(TextEdit("旧地点与标签", 2), store.openDraft(draft))
            assertEquals(PlaceCoordinates(30.67, 104.06), store.openDraftPlace(draft)!!.info.coordinates)
            val person = store.createDraftObject(draft, ObjectKind.PERSON, ObjectInfo("外婆", aliases = listOf("阿婆")))
            val record = store.completeMixedDraft(draft)
            assertEquals(person, store.openRecordContent(record)!!.objects.single().id)
            assertEquals("旧标签", store.openRecordContent(record)!!.tags.single().name)
            assertEquals(PlaceCoordinates(30.67, 104.06), store.openRecordContent(record)!!.place!!.info.coordinates)
            assertEquals(listOf(record), store.search("阿婆").map { it.id })
        } finally { database.close() }
    }

    @Test
    fun versionFourteenRetainsExactDateTimeAndUndatedDraftsWhenAddingTimeExpressions() = runBlocking {
        val date = LocalDate.of(2025, 11, 8)
        val clock = LocalTime.of(0, 30, 12, 345_000_000)
        val zone = ZoneId.of("Asia/Tokyo")
        createDatabase(14) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,eventDate,eventClock,eventZone)
                VALUES ('record','source','夜宵',100,200,?,?,?)""",
                arrayOf<Any>(date.toEpochDay(), clock.toNanoOfDay(), zone.id))
            sql.execSQL("INSERT INTO record_items (recordId,position,itemId,text) VALUES ('record',0,'item','夜宵')")
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,eventDate,eventZone)
                VALUES ('edit','夜宵',2,100,300,?,?)""", arrayOf<Any>(date.toEpochDay(), zone.id))
            sql.execSQL("INSERT INTO draft_items (draftId,position,itemId,text) VALUES ('edit',0,'item','夜宵')")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',1)")
            sql.execSQL("INSERT INTO text_drafts (id,body,cursor,firstRecordedAt,lastEditedAt) VALUES ('undated','补记',2,150,150)")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context)
            val record = RecordId("record")
            val edit = DraftId("edit")
            val published = RecordTimes(100L, 200L, EventTime(date, clock, zone))
            assertEquals(published, store.openRecordContent(record)!!.times)
            assertEquals(DraftTimes(100L, EventTime(date, zone = zone)), store.openDraftTimes(edit))
            assertEquals(DraftTimes(150L, null), store.openDraftTimes(DraftId("undated")))
            val winter = EventTime.Season(2025, NamedSeason.WINTER)
            store.saveDraftEventTime(edit, winter)
            assertEquals(published, store.openRecordContent(record)!!.times)
            assertEquals(record, store.completeMixedDraft(edit))
            assertEquals(winter, store.openRecordContent(record)!!.times.eventTime)
        } finally {
            database.close()
        }
    }

    @Test
    fun versionSixteenKeepsDayPrecisionAndNewBoundsKeepTheirOwnPrecision() = runBlocking {
        val start = LocalDate.parse("2018-01-01")
        val end = LocalDate.parse("2022-06-30")
        val zone = ZoneId.of("Asia/Tokyo")
        createDatabase(16) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,eventKind,eventDate,eventEndDate,eventZone)
                VALUES ('record','source','校园',100,200,'range',${start.toEpochDay()},${end.toEpochDay()},'Asia/Tokyo')""")
            sql.execSQL("INSERT INTO record_items (recordId,position,itemId,text) VALUES ('record',0,'item','校园')")
        }
        val id = RecordId("record")
        val precision = RangePrecision(DatePrecision.YEAR, DatePrecision.MONTH)
        val college = EventTime.Description("大学时", start..end, zone, precision)
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        val edit = try {
            val store = LocalTextStore(database, context)
            assertEquals(EventTime.Range(start, end, zone), store.openRecordContent(id)!!.times.eventTime)
            store.editRecord(id).also { store.saveDraftEventTime(it, college) }
        } finally { database.close() }
        val reopened = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(reopened, context)
            assertEquals(college, store.openDraftTimes(edit)!!.eventTime)
            store.completeMixedDraft(edit)
            assertEquals(college, store.queryRecords("大学时").single().eventTime)
            assertEquals("2018" to "2022-06", college.precision.inputs(requireNotNull(college.dateRange)))
        } finally { reopened.close() }
    }

    @Test
    fun versionFifteenKeepsUntitledRecordAndPendingEditWhenTitlesAreAdded() = runBlocking {
        createDatabase(15) { sql ->
            sql.execSQL("""INSERT INTO text_records
                (id,sourceDraftId,body,firstRecordedAt,modifiedAt,eventKind,eventText,eventZone)
                VALUES ('record','source','校门口',100,200,'description','大学时','Asia/Tokyo')""")
            sql.execSQL("INSERT INTO record_items (recordId,position,itemId,text) VALUES ('record',0,'item','校门口')")
            sql.execSQL("""INSERT INTO text_drafts
                (id,body,cursor,firstRecordedAt,lastEditedAt,activeItemId,eventKind,eventText,eventZone)
                VALUES ('edit','校门口的书店',3,100,300,'item','description','大学时','Asia/Tokyo')""")
            sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','record',1)")
            sql.execSQL("INSERT INTO draft_items (draftId,position,itemId,text) VALUES ('edit',0,'item','校门口的书店')")
            sql.execSQL("INSERT INTO tags VALUES ('tag','校园','edit')")
            sql.execSQL("INSERT INTO draft_tags VALUES ('edit','tag',0)")
        }
        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database, context) { 400L }
            val id = RecordId("record")
            val edit = DraftId("edit")
            val time = EventTime.Description("大学时", zone = ZoneId.of("Asia/Tokyo"))
            val original = store.openRecordContent(id)!!
            assertNull(original.title)
            assertEquals("校门口", original.text)
            assertEquals(RecordTimes(100L, 200L, time), original.times)
            assertEquals(edit, store.editRecord(id))
            assertNull(store.openMixedDraft(edit)!!.title)
            assertEquals(TextEdit("校门口的书店", 3), store.openDraft(edit))
            assertEquals(DraftTimes(100L, time), store.openDraftTimes(edit))
            assertEquals("校园", store.openDraftTags(edit)!!.single().name)

            store.saveDraftTitle(edit, "旧书新读")
            assertTrue(store.queryRecords("旧书新读").isEmpty())
            assertEquals(id, store.completeMixedDraft(edit))
            val card = store.queryRecords("旧书新读").single()
            assertEquals(id, card.id)
            assertEquals("旧书新读", card.title)
            assertEquals("校园", card.tags.single().name)
            val published = store.openRecordContent(id)!!
            assertEquals("校门口的书店", published.text)
            assertEquals(RecordTimes(100L, 400L, time), published.times)
        } finally { database.close() }
    }

    @Test
    fun versionSeventeenKeepsOriginalsDraftsUndoAndTrashWhenAddingPdf() = runBlocking {
        val photo = PhotoId(UUID.randomUUID())
        val photoFile = File(context.filesDir, "photo-originals/${photo.value}")
        photoFile.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        photoFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val photoBytes = photoFile.readBytes()
        val audio = createAudioTestClip(context)
        val originals = LocalAudioOriginals.get(context)
        val audioBytes = originals.openOriginal(audio.id).use { it.readBytes() }
        try {
            createDatabase(17) { sql ->
                sql.execSQL("""INSERT INTO text_records(id,sourceDraftId,body,firstRecordedAt,modifiedAt,isStarred,deletedAt)
                    VALUES ('trip','s1','秋游',100,200,1,NULL), ('ticket','s2','旧车票',110,210,0,300)""")
                sql.execSQL("""INSERT INTO record_items(recordId,position,itemId,text)
                    VALUES ('trip',0,'t','秋游'), ('ticket',0,'ticket-text','旧车票')""")
                sql.execSQL("INSERT INTO record_items(recordId,position,itemId,photoId) VALUES ('trip',1,'p',?)",
                    arrayOf(photo.value.toString()))
                sql.execSQL("INSERT INTO record_items(recordId,position,itemId,audioId,audioDurationMillis) VALUES ('trip',2,'a',?,?)",
                    arrayOf<Any>(audio.id.value.toString(), audio.durationMillis))
                sql.execSQL("""INSERT INTO text_drafts(id,body,cursor,firstRecordedAt,lastEditedAt,firstContentAt,activeItemId)
                    VALUES ('edit','秋游' || char(10) || '补记山路',7,100,240,100,'more'),
                    ('rain','明天带伞',4,220,220,220,'rain-text'), ('hidden','票面铅笔字',5,110,230,110,'ticket-text')""")
                sql.execSQL("INSERT INTO text_record_edits VALUES ('edit','trip',1), ('hidden','ticket',1)")
                sql.execSQL("""INSERT INTO draft_items(draftId,position,itemId,text,photoId,audioId,audioDurationMillis)
                    SELECT 'edit',position,itemId,text,photoId,audioId,audioDurationMillis FROM record_items WHERE recordId='trip'""")
                sql.execSQL("""INSERT INTO draft_items(draftId,position,itemId,text) VALUES
                    ('edit',3,'more','补记山路'), ('rain',0,'rain-text','明天带伞'), ('hidden',0,'ticket-text','票面铅笔字')""")
                sql.execSQL("""INSERT INTO removed_draft_items(draftId,position,cursor,itemId,text)
                    VALUES ('rain',1,0,'undo','带地图')""")
            }
            val db = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
            try {
                val store = LocalTextStore(db, context)
                val trip = store.openRecordContent(RecordId("trip"))!!
                assertTrue(store.listGroups().isEmpty())
                val group = RecordGroupId("legacy-trips")
                assertEquals(GroupUpdate.Saved(group, listOf(RecordId("trip"))),
                    store.createGroup(group, "秋天", listOf(RecordId("trip"))))
                assertTrue(trip.marks.isStarred)
                assertEquals(RecordTimes(100, 200, null), trip.times)
                assertEquals(listOf(ItemId("t"), ItemId("p"), ItemId("a")), trip.items.map { it.id })
                assertArrayEquals(photoBytes, LocalPhotoOriginals.get(context).openOriginal(trip.photos.single()).use { it.readBytes() })
                val sound = trip.items.last() as ContentItem.Audio
                assertEquals(audio.durationMillis, sound.durationMillis)
                assertArrayEquals(audioBytes, originals.openOriginal(sound.audioId).use { it.readBytes() })
                assertEquals(setOf(DraftId("edit"), DraftId("rain")), store.listDrafts().map { it.id }.toSet())
                assertEquals(ItemId("more"), store.openMixedDraft(DraftId("edit"))!!.activeItemId)
                assertTrue(store.search("补记山路").isEmpty())
                assertTrue(store.undoRemoval(DraftId("rain")))
                assertEquals("明天带伞\n带地图", store.openDraft(DraftId("rain"))!!.text)
                val ticket = RecordId("ticket")
                assertTrue(store.openTrashRecord(ticket)!!.hasDraft)
                assertNull(store.openMixedDraft(DraftId("hidden")))
                assertTrue(store.restoreRecord(ticket))
                assertEquals("票面铅笔字", store.openDraft(DraftId("hidden"))!!.text)
                assertEquals(listOf(RecordId("trip")), store.openGroup(group)!!.members.map { it.id })
                assertEquals(0, store.retryImportCleanup())
            } finally { db.close() }
        } finally {
            photoFile.delete()
            originals.deleteOriginal(audio.id)
        }
    }

    @Test
    fun versionEighteenPdfCanJoinAGroupAndStillOpensAfterRecovery() = runBlocking {
        val pdfId = UUID.randomUUID()
        val file = File(context.filesDir, "pdf-originals/$pdfId.pdf")
        file.parentFile!!.mkdirs()
        val nameInRecord = "窗框凭证.pdf"
        val bytes = instrumentation.context.assets.open("m01/$nameInRecord").use { it.readBytes() }
        file.writeBytes(bytes)
        try {
            createDatabase(18) { sql ->
                sql.execSQL("""INSERT INTO text_records(id,sourceDraftId,body,firstRecordedAt,modifiedAt)
                    VALUES ('receipt','source','',100,200)""")
                sql.execSQL("""INSERT INTO record_items(recordId,position,itemId,pdfId,pdfName,pdfSizeBytes)
                    VALUES ('receipt',0,'pdf',?,?,?)""", arrayOf<Any>(pdfId.toString(), nameInRecord, bytes.size))
            }
            val db = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
            try {
                val store = LocalTextStore(db, context)
                val receipt = RecordId("receipt")
                val group = RecordGroupId("receipts")
                assertTrue(store.listGroups().isEmpty())
                assertEquals(GroupUpdate.Saved(group, listOf(receipt)), store.createGroup(group, "凭证", listOf(receipt)))
                assertTrue(store.moveToTrash(receipt))
                assertTrue(store.openGroup(group)!!.members.isEmpty())
                assertTrue(store.restoreRecord(receipt))
                val card = store.openGroup(group)!!.members.single()
                assertEquals(receipt, card.id)
                assertEquals(PreviewContentItem.Pdf(ItemId("pdf"), nameInRecord), card.preview.items.single())
                val content = store.openRecordContent(receipt)!!
                assertEquals(RecordTimes(100, 200, null), content.times)
                val pdf = content.items.single() as ContentItem.Pdf
                assertEquals(pdfId, pdf.pdfId.value)
                assertEquals(bytes.size.toLong(), pdf.sizeBytes)
                assertArrayEquals(bytes, store.openPdf(pdf.pdfId).use { it.readBytes() })
            } finally { db.close() }
        } finally { file.delete() }
    }

    @Test
    fun versionNineteenGroupsKeepTheirOrderAndTrashedMembershipWhenEnablingGuides() = runBlocking {
        createDatabase(19) { sql ->
            sql.execSQL("""INSERT INTO text_records(id,sourceDraftId,body,firstRecordedAt,modifiedAt,deletedAt)
                VALUES ('buy','b','购买凭证',1,1,NULL),('repair','r','维修单',2,2,3)""")
            sql.execSQL("INSERT INTO record_items(recordId,position,itemId,text) SELECT id,0,id,body FROM text_records")
            sql.execSQL("INSERT INTO record_groups VALUES ('machine','购机资料')")
            sql.execSQL("""INSERT INTO record_group_members(groupId,recordId,position,previousRecordId)
                VALUES ('machine','buy',0,NULL),('machine','repair',NULL,'buy')""")
        }
        val db = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(db, context)
            val group = RecordGroupId("machine")
            val buy = RecordId("buy")
            val repair = RecordId("repair")
            val guide = requireNotNull(store.openGuide(group))
            assertEquals("购机资料", guide.content.group.name)
            assertEquals(GuideLayout("", emptyList(), listOf(GuideMember(buy))), guide.layout)
            val section = GuideSection(GuideSectionId("purchase"), "购买")
            store.saveGuide(group, guide.layout, guide.layout.copy(sections = listOf(section), members = listOf(GuideMember(buy, section.id))))
            assertTrue(store.restoreRecord(repair))
            assertEquals(listOf(GuideMember(buy, section.id), GuideMember(repair)), store.openGuide(group)!!.layout.members)
            assertEquals("维修单", store.openRecord(repair))
        } finally { db.close() }
    }

    private fun createDatabase(version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schemaFile = "${FolioDatabase::class.java.name}/$version.json"
        val schema = instrumentation.context.assets.open(schemaFile).bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.optJSONArray("views")?.let { views ->
                for (i in 0 until views.length()) {
                    val view = views.getJSONObject(i)
                    sql.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            seed(sql)
            sql.version = schema.getInt("version")
        }
    }
}
