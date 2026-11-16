package com.baconaa.folio.records

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.media.LocalAudioOriginals
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.media.createAudioTestClip
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context))
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context))
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
            val store = LocalTextStore(db, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context))
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context))
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context), now = { 600L })
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = LocalAudioOriginals.get(context), now = { 600L })
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
            val store = LocalTextStore(database, LocalPhotoOriginals.get(context), audioOriginals = originals)
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
