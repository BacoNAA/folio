package com.baconaa.folio.records

import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.*
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*

internal class BackupPreview internal constructor(
    val saved: BackupSummary, val target: BackupSummary, val created: Long, val version: String,
    internal val targetFingerprint: ByteArray, internal val fileFingerprint: ByteArray,
    internal val directory: File, internal val originals: Map<OriginalRef, File>,
)

internal suspend fun LocalBackups.preview(uri: Uri, password: CharArray): BackupPreview = preview(password) {
    requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件。" }
}

internal suspend fun LocalBackups.preview(password: CharArray, open: () -> InputStream): BackupPreview = withContext(Dispatchers.IO) {
    val dir = temporaryDirectory()
    try {
        val archive = File(dir, "import.folio")
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            archive.outputStream().buffered().use { target ->
                BackupCipher.decrypt(java.security.DigestInputStream(input, digest), target, password, dir.usableSpace - 32L * 1024 * 1024)
            }
        }
        val metadata = unpackChecked(archive, dir)
        val candidate = Room.databaseBuilder(context, FolioDatabase::class.java, File(dir, "candidate.db").absolutePath).build()
        val (summary, originals) = try {
            candidate.withTransaction {
                val rows = BackupRows(candidate.openHelper.writableDatabase)
                File(dir, "records.bin").inputStream().buffered().use { rows.replace(DataInputStream(it)) }
                validateBackupData(candidate)
                val found = rows.originals().associateWith { ref ->
                    val paths = ref.paths().filter { it in metadata.members }
                    require(paths.size == 1) { "备份缺少媒体或包含重复原件。" }
                    File(dir, paths.single()).also { validateOriginal(context, ref, it) }
                }
                require(found.size + 1 == metadata.members.size) { "备份包含未知的媒体引用。" }
                require(candidate.openHelper.writableDatabase.query("SELECT enlarged FROM reading_preference WHERE id=1").use {
                    it.moveToFirst() && it.getLong(0) in 0..1
                }) { "备份缺少有效阅读设置。" }
                rows.summary() to found
            }
        } finally { candidate.close() }
        val target = database.withTransaction {
            ReadingSettings(database, context).current()
            val rows = BackupRows(database.openHelper.writableDatabase)
            rows.summary() to fingerprint(rows)
        }
        BackupPreview(summary, target.first, metadata.created, metadata.version, target.second, digest.digest(), dir, originals)
    } catch (failure: Throwable) { release(dir); throw failure }
}

private data class Metadata(val created: Long, val version: String, val members: Set<String>)
private const val uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val originalPath = Regex("(?:photo-originals/$uuidPattern|audio-originals/$uuidPattern\\.(?:audio|m4a)|pdf-originals/$uuidPattern\\.pdf|video-originals/$uuidPattern\\.mp4)")

private suspend fun unpackChecked(archive: File, dir: File): Metadata = ZipFile(archive).use { zip ->
    val manifest = requireNotNull(zip.getEntry("manifest")) { "备份未写完或格式不受支持。" }
    require(manifest.size in 1..(32L * 1024 * 1024)) { "备份清单过大或为空。" }
    DataInputStream(zip.getInputStream(manifest).buffered()).use { input ->
        require(input.readUTF() == "Folio backup" && input.readInt() == 1) { "备份格式不受支持，请核对应用版本。" }
        val created = input.readLong()
        val version = input.readUTF()
        val count = input.readInt()
        require(count in 1..250_000 && count == zip.size() - 1) { "备份项目数不符。" }
        val names = mutableSetOf<String>()
        repeat(count) {
            val path = input.readUTF()
            require(names.add(path) && (path == "records.bin" || originalPath.matches(path))) { "备份包含无效的原件名。" }
            val length = input.readLong()
            val hash = ByteArray(32).also(input::readFully)
            val entry = requireNotNull(zip.getEntry(path)) { "备份缺少所需文件。" }
            require(length > 0 && entry.size == length && length <= dir.usableSpace - 32L * 1024 * 1024) { "文件长度无效或手机剩余空间不足。" }
            val target = File(dir, path)
            target.parentFile!!.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { source ->
                target.outputStream().use { out -> copyBytes(source, out, digest, length); out.fd.sync() }
            }
            require(target.length() == length && hash.contentEquals(digest.digest())) { "备份校验失败，请重新选择完整文件。" }
        }
        require(input.read() == -1 && "records.bin" in names) { "备份缺少记录。" }
        Metadata(created, version, names)
    }
}

private fun fingerprint(rows: BackupRows): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val discard = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(bytes: ByteArray, off: Int, len: Int) = Unit
    }
    DataOutputStream(DigestOutputStream(discard, digest).buffered()).use(rows::write)
    return digest.digest()
}

internal suspend fun LocalBackups.restore(preview: BackupPreview, uri: Uri) =
    restore(preview) { requireNotNull(context.contentResolver.openInputStream(uri)) }

internal suspend fun LocalBackups.restore(preview: BackupPreview, open: () -> InputStream) = withContext(Dispatchers.IO) {
    require(open().use { digestStream(it) }.contentEquals(preview.fileFingerprint)) { "所选文件已经改变，请重新预检。" }
    val originals = preview.originals
    // A durable cleanup candidate precedes each new file. If the process dies before SQLite commits,
    // the previous rows remain intact and existing reference-aware cleanup can reclaim orphaned bytes.
    database.withTransaction {
        val db = database.openHelper.writableDatabase
        originals.keys.forEach { enqueue(db, it) }
    }
    database.withTransaction {
        val db = database.openHelper.writableDatabase
        val rows = BackupRows(db)
        require(fingerprint(rows).contentEquals(preview.targetFingerprint)) { "本机资料已经改变，请重新预检并确认。" }
        rows.originals().forEach { enqueue(db, it) }
        val publishedFolders = mutableSetOf<File>()
        for ((ref, staged) in originals) {
            val existing = ref.paths().map { File(context.filesDir, it) }.filter(File::exists)
            if (existing.isEmpty()) {
                require(isQueued(db, ref)) { "媒体状态已经变化，请重新预检。" }
                val destination = File(context.filesDir, staged.relativeTo(preview.directory).path)
                destination.parentFile!!.mkdirs()
                Files.move(staged.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                publishedFolders.add(destination.parentFile!!)
            } else {
                val bytes = staged.inputStream().use { digestStream(it) }
                require(existing.all { file -> file.inputStream().use { digestStream(it) }.contentEquals(bytes) }) {
                    "同一原件标识对应不同文件，已保留本机资料。"
                }
            }
        }
        for (folder in publishedFolders) {
            val fd = Os.open(folder.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        File(preview.directory, "records.bin").inputStream().buffered().use { rows.replace(DataInputStream(it)) }
    }
    // Rows are already committed. A cleanup failure remains retryable from the recycle bin.
    try { LocalTextStore(database, context).retryMediaCleanup() }
    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { -1 }
}

private fun queue(ref: OriginalRef) = when (ref.kind) {
    "photo" -> "photo_cleanup_queue" to "photoId"
    "audio" -> "audio_cleanup_queue" to "audioId"
    else -> "import_cleanup_queue" to "assetId"
}

private fun enqueue(db: SupportSQLiteDatabase, ref: OriginalRef) {
    val (table, column) = queue(ref)
    if (table == "import_cleanup_queue") {
        db.execSQL("INSERT OR IGNORE INTO import_cleanup_queue(assetId,type) VALUES(?,?)", arrayOf(ref.id.toString(),ref.kind.uppercase(java.util.Locale.ROOT)))
    } else db.execSQL("INSERT OR IGNORE INTO $table($column) VALUES(?)", arrayOf(ref.id.toString()))
}

private fun isQueued(db: SupportSQLiteDatabase, ref: OriginalRef): Boolean {
    val (table, column) = queue(ref)
    return db.query("SELECT 1 FROM $table WHERE $column=?", arrayOf(ref.id.toString())).use { it.moveToFirst() }
}
