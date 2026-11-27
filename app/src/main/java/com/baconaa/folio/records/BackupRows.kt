package com.baconaa.folio.records

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Only local schema metadata supplies SQL identifiers. The file contains typed values, never SQL. */
internal class BackupRows(private val db: SupportSQLiteDatabase) {
    private val tables = mutableMapOf<String, List<String>>().apply {
        val sql = """SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
            AND name NOT IN ('android_metadata','room_master_table','room_table_modification_log',
            'photo_cleanup_queue','audio_cleanup_queue','import_cleanup_queue') ORDER BY name"""
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                require(name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                put(name, db.query("SELECT * FROM `$name` LIMIT 0").use { it.columnNames.sorted() })
            }
        }
    }

    fun write(out: DataOutputStream) {
        out.writeInt(db.version)
        out.writeInt(tables.size)
        tables.forEach { (name, columns) ->
            out.writeUTF(name)
            out.writeInt(columns.size)
            columns.forEach(out::writeUTF)
            val columnList = columns.joinToString { "`$it`" }
            db.query("SELECT $columnList FROM `$name` ORDER BY rowid").use { rows ->
                while (rows.moveToNext()) {
                    out.writeBoolean(true)
                    columns.indices.forEach { index ->
                        val type = rows.getType(index)
                        out.writeByte(type)
                        when (type) {
                            Cursor.FIELD_TYPE_NULL -> Unit
                            Cursor.FIELD_TYPE_INTEGER -> out.writeLong(rows.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> out.writeDouble(rows.getDouble(index))
                            Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_BLOB -> {
                                val bytes = if (type == Cursor.FIELD_TYPE_STRING) rows.getString(index).toByteArray() else rows.getBlob(index)
                                require(bytes.size <= MAX_FIELD_BYTES) { "记录字段超过备份格式限制。" }
                                out.writeInt(bytes.size)
                                out.write(bytes)
                            }
                            else -> error("无法导出未知的数据类型。")
                        }
                    }
                }
                out.writeBoolean(false)
            }
        }
    }

    fun replace(input: DataInputStream) {
        val version = input.readInt()
        require(version in 30..db.version) { "此备份的数据版本与本应用不兼容。" }
        // 每次仅新增表的迁移都可读旧包；旧版本本应具备的表仍须全部存在。
        val expected = tables.filterKeys { (introducedIn[it] ?: 30) <= version }
        require(input.readInt() == expected.size) { "备份数据表不完整。" }
        db.execSQL("PRAGMA defer_foreign_keys=ON")
        tables.keys.forEach { db.execSQL("DELETE FROM `$it`") }
        expected.forEach { (name, columns) ->
            require(input.readUTF() == name && input.readInt() == columns.size && columns.all { input.readUTF() == it }) {
                "备份数据结构不匹配。"
            }
            val placeholders = columns.joinToString { "?" }
            val columnList = columns.joinToString { "`$it`" }
            db.compileStatement("INSERT INTO `$name` ($columnList) VALUES ($placeholders)").use { insert ->
                while (input.readBoolean()) {
                    columns.indices.forEach { index ->
                        val position = index + 1
                        when (val type = input.readUnsignedByte()) {
                            Cursor.FIELD_TYPE_NULL -> insert.bindNull(position)
                            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(position, input.readLong())
                            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(position, input.readDouble())
                            Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_BLOB -> {
                                val size = input.readInt()
                                require(size in 0..MAX_FIELD_BYTES) { "备份字段长度无效。" }
                                val bytes = ByteArray(size).also(input::readFully)
                                if (type == Cursor.FIELD_TYPE_STRING) insert.bindString(position, bytes.toString(Charsets.UTF_8))
                                else insert.bindBlob(position, bytes)
                            }
                            else -> error("无法识别备份字段。")
                        }
                    }
                    insert.executeInsert()
                }
            }
        }
        require(input.read() == -1) { "备份数据末尾有未识别内容。" }
        db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份中的记录关联不完整。" } }
    }

    fun summary(): BackupSummary {
        fun count(sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
        return BackupSummary(count("SELECT count(*) FROM text_records WHERE deletedAt IS NULL"),
            count("SELECT count(*) FROM editable_text_drafts WHERE id NOT IN (SELECT draftId FROM text_record_edits) OR id IN (SELECT draftId FROM record_split_items)"),
            count("SELECT count(*) FROM editable_text_drafts WHERE id IN (SELECT draftId FROM text_record_edits) AND id NOT IN (SELECT draftId FROM record_split_items)"),
            count("SELECT count(*) FROM text_records WHERE deletedAt IS NOT NULL"))
    }

    fun originals(): Set<OriginalRef> = buildSet {
        tables.forEach { (table, columns) ->
            columns.filter { it in originalColumns }.forEach { column ->
                db.query("SELECT DISTINCT `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) add(OriginalRef(originalColumns.getValue(column), UUID.fromString(cursor.getString(0))))
                }
            }
        }
    }

    companion object {
        private val introducedIn = mapOf(
            "context_sets" to 35, "context_tags" to 35, "context_feelings" to 35,
            "tag_stamps" to 34,
            "draft_feelings" to 33, "feeling_scales" to 33, "place_feelings" to 33, "place_impressions" to 33, "record_feelings" to 33, "template_feeling_scales" to 33,
            "custom_states" to 32, "state_changes" to 32, "target_states" to 32,
            "place_tasks" to 31, "task_experiences" to 31, "task_experience_drafts" to 31,
        )
        private const val MAX_FIELD_BYTES = 16_777_216
        private val originalColumns = mapOf("photoId" to "photo", "linkScreenshotId" to "photo", "audioId" to "audio",
            "pdfId" to "pdf", "videoId" to "video")
    }
}

internal data class BackupSummary(val records: Long, val drafts: Long, val edits: Long, val trash: Long) {
    fun label() = "正式记录 $records 条，新草稿 $drafts 份，编辑草稿 $edits 份，回收站 $trash 条"
}

internal data class OriginalRef(val kind: String, val id: UUID) {
    fun paths() = when (kind) {
        "photo" -> listOf("photo-originals/$id")
        "audio" -> listOf("audio-originals/$id.audio", "audio-originals/$id.m4a")
        "pdf" -> listOf("pdf-originals/$id.pdf")
        "video" -> listOf("video-originals/$id.mp4")
        else -> error("无法识别媒体类型。")
    }
}
