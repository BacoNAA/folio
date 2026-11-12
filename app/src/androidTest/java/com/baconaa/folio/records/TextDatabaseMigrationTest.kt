package com.baconaa.folio.records

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, FolioDatabase::class.java, name).build()
        try {
            val store = LocalTextStore(database)
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

    private fun createVersionOneDatabase() {
        val schemaFile = "${FolioDatabase::class.java.name}/1.json"
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
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO text_drafts VALUES ('unfinished', '等雨停', 1, 300, 400)")
            sql.execSQL("INSERT INTO text_records VALUES ('published', 'submitted', '旧桥', 100, 200, NULL)")
            sql.version = schema.getInt("version")
        }
    }
}
