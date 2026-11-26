package com.baconaa.folio.records

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlin.coroutines.cancellation.CancellationException

@Entity(tableName = "reading_preference")
internal data class ReadingPreference(@PrimaryKey val id: Int, val enlarged: Boolean)

internal class ReadingSettings(private val database: FolioDatabase, private val context: Context) {
    // Earlier versions stored this setting outside Room. A restored preference always takes precedence.
    internal fun current(): Boolean {
        val db = database.openHelper.writableDatabase
        db.query("SELECT enlarged FROM reading_preference WHERE id=1").use {
            if (it.moveToFirst()) return it.getInt(0) != 0
        }
        val initial = context.getSharedPreferences("reading_settings", Context.MODE_PRIVATE).getBoolean("enlarge_body", false)
        db.execSQL("INSERT INTO reading_preference(id,enlarged) VALUES(1,?)", arrayOf(if (initial) 1 else 0))
        return initial
    }

    suspend fun readEnlargedBody(): Boolean? = try {
        database.withTransaction { current() }
    } catch (cancel: CancellationException) { throw cancel } catch (_: RuntimeException) { null }

    suspend fun saveEnlargedBody(enlarged: Boolean): Boolean = try {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO reading_preference(id,enlarged) VALUES(1,?)",
                arrayOf(if (enlarged) 1 else 0))
        }
        true
    } catch (cancel: CancellationException) { throw cancel } catch (_: RuntimeException) { false }
}
