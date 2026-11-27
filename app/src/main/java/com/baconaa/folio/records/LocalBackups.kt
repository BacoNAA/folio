package com.baconaa.folio.records

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.withTransaction
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

internal data class ExportedBackup(val uri: Uri, val name: String, val summary: BackupSummary)

internal class LocalBackups(internal val database: FolioDatabase, internal val context: Context) {
    private val resolver get() = context.contentResolver

    /** Create a new child document. ACTION_CREATE_DOCUMENT can also return an existing overwrite target. */
    suspend fun export(tree: Uri, password: CharArray): ExportedBackup = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val existing = mutableSetOf<String>()
        requireNotNull(resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)).use {
            while (it.moveToNext()) existing.add(it.getString(0))
        }
        val title = "Folio-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".folio"
        val uri = requireNotNull(DocumentsContract.createDocument(resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parent), "application/octet-stream", title)) { "无法在这个位置新建文件。" }
        require(DocumentsContract.getDocumentId(uri) !in existing) { "此位置只允许覆盖已有文件，请另选目录。" }
        val name = requireNotNull(resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE), null, null, null)).use {
            require(it.moveToFirst() && (it.isNull(1) || it.getLong(1) == 0L)) { "目标已有内容，请另选位置。" }
            it.getString(0)
        }
        try {
            val expected = MessageDigest.getInstance("SHA-256")
            val summary = requireNotNull(resolver.openOutputStream(uri, "w")).use { sink ->
                writeProtected(java.security.DigestOutputStream(sink, expected), password)
            }
            val stored = requireNotNull(resolver.openInputStream(uri)).use { digestStream(it) }
            check(expected.digest().contentEquals(stored)) { "导出的文件未完整保存，请换一个位置重试。" }
            ExportedBackup(uri, name, summary)
        } catch (failure: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw failure
        }
    }

    internal suspend fun writeProtected(output: OutputStream, password: CharArray): BackupSummary = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "请使用至少八个字符的密码。" }
        val dir = temporaryDirectory()
        try {
            val file = File(dir, "snapshot.zip")
            val summary = file.outputStream().use { writeArchive(it) }
            file.inputStream().buffered().use { BackupCipher.encrypt(it, output, password) }
            summary
        } finally { release(dir) }
    }

    internal suspend fun writeArchive(output: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        val directory = temporaryDirectory()
        try {
            val rows = File(directory, "records.bin")
            val originals = linkedMapOf<String, File>()
            val summary = database.withTransaction {
                val db = database.openHelper.writableDatabase
                ReadingSettings(database, context).current()
                validateBackupData(database)
                val data = BackupRows(db)
                DataOutputStream(rows.outputStream().buffered()).use(data::write)
                data.originals().forEach { ref ->
                    ensureActive()
                    val path = ref.paths().firstOrNull { File(context.filesDir, it).isFile }
                        ?: error("缺少${ref.kind}原件，无法建立完整备份。")
                    val pinned = File(directory, path)
                    pinned.parentFile!!.mkdirs()
                    // Android may deny hard links between filesDir and cacheDir. Copy while the
                    // Room transaction still protects the reference from concurrent cleanup.
                    File(context.filesDir, path).inputStream().use { input ->
                        pinned.outputStream().use { output -> copyBytes(input, output, null) }
                    }
                    originals[path] = pinned
                }
                data.summary()
            }
            originals.forEach { (path, file) ->
                val kind = path.substringBefore("-")
                val id = UUID.fromString(file.name.substringBefore('.'))
                validateOriginal(context, OriginalRef(kind, id), file)
            }
            val sources = linkedMapOf("records.bin" to rows).apply { putAll(originals) }
            require(sources.size <= 250_000) { "原件数量超过此备份格式的容量。" }
            val manifest = ByteArrayOutputStream()
            ZipOutputStream(output.buffered()).use { archive ->
                DataOutputStream(manifest).use { end ->
                    end.writeUTF("Folio backup")
                    end.writeInt(1)
                    end.writeLong(System.currentTimeMillis())
                    end.writeUTF(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
                    end.writeInt(sources.size)
                    sources.forEach { (name, file) ->
                        archive.putNextEntry(ZipEntry(name))
                        val digest = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { copyBytes(it, archive, digest) }
                        archive.closeEntry()
                        end.writeUTF(name)
                        end.writeLong(file.length())
                        end.write(digest.digest())
                    }
                }
                require(manifest.size() <= 32 * 1024 * 1024) { "清单超过此备份格式的容量。" }
                archive.putNextEntry(ZipEntry("manifest"))
                archive.write(manifest.toByteArray())
                archive.closeEntry()
            }
            summary
        } finally { release(directory) }
    }

    internal fun temporaryDirectory(): File {
        val parent = File(context.cacheDir, "folio-backups")
        synchronized(activeDirectories) {
            parent.mkdirs()
            // Cache residues of interrupted jobs belong only to this process' predecessor.
            parent.listFiles()?.filter { it !in activeDirectories }?.forEach { it.deleteRecursively() }
            return File(parent, UUID.randomUUID().toString()).also {
                check(it.mkdir()) { "无法准备备份空间。" }
                activeDirectories.add(it)
            }
        }
    }

    internal fun release(directory: File) {
        synchronized(activeDirectories) { directory.deleteRecursively(); activeDirectories.remove(directory) }
    }

    private companion object { val activeDirectories = mutableSetOf<File>() }
}

internal suspend fun copyBytes(source: InputStream, target: OutputStream?, digest: MessageDigest?, limit: Long = Long.MAX_VALUE) {
    val job = currentCoroutineContext()
    var remaining = limit
    val buffer = ByteArray(64 * 1024)
    while (true) {
        job.ensureActive()
        val n = source.read(buffer)
        if (n < 0) return
        require(n <= remaining) { "文件长度超过备份声明。" }
        remaining -= n
        target?.write(buffer, 0, n)
        digest?.update(buffer, 0, n)
    }
}

internal suspend fun digestStream(input: InputStream): ByteArray = MessageDigest.getInstance("SHA-256").also {
    copyBytes(input, null, it)
}.digest()
