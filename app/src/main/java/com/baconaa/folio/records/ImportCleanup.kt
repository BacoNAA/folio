package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.baconaa.folio.media.ImportedOriginals
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Entity(tableName = "import_cleanup_queue")
internal data class ImportCleanupEntry(@PrimaryKey val assetId: String, val type: ImportType)

@Dao
internal interface ImportCleanupDao {
    @Insert
    suspend fun reserve(entry: ImportCleanupEntry)

    @Query("""INSERT OR IGNORE INTO import_cleanup_queue(assetId, type)
              SELECT pdfId, 'PDF' FROM draft_items WHERE draftId = :id AND pdfId IS NOT NULL
              UNION SELECT pdfId, 'PDF' FROM removed_draft_items WHERE draftId = :id AND pdfId IS NOT NULL
              UNION SELECT videoId, 'VIDEO' FROM draft_items WHERE draftId = :id AND videoId IS NOT NULL
              UNION SELECT videoId, 'VIDEO' FROM removed_draft_items WHERE draftId = :id AND videoId IS NOT NULL""")
    suspend fun enqueueDraft(id: String)

    @Query("""INSERT OR IGNORE INTO import_cleanup_queue(assetId, type)
              SELECT pdfId, 'PDF' FROM record_items WHERE recordId = :id AND pdfId IS NOT NULL
              UNION SELECT videoId, 'VIDEO' FROM record_items WHERE recordId = :id AND videoId IS NOT NULL""")
    suspend fun enqueueRecord(id: String)

    @Query("""SELECT EXISTS(SELECT 1 FROM record_items WHERE pdfId = :id
              UNION ALL SELECT 1 FROM draft_items WHERE pdfId = :id
              UNION ALL SELECT 1 FROM removed_draft_items WHERE pdfId = :id)""")
    suspend fun pdfReferenced(id: String): Boolean

    @Query("""SELECT EXISTS(SELECT 1 FROM record_items WHERE videoId = :id
              UNION ALL SELECT 1 FROM draft_items WHERE videoId = :id
              UNION ALL SELECT 1 FROM removed_draft_items WHERE videoId = :id)""")
    suspend fun videoReferenced(id: String): Boolean

    @Query("SELECT assetId FROM import_cleanup_queue")
    suspend fun candidates(): List<String>

    @Query("SELECT * FROM import_cleanup_queue WHERE assetId = :id")
    suspend fun entry(id: String): ImportCleanupEntry?

    @Query("DELETE FROM import_cleanup_queue WHERE assetId = :id")
    suspend fun remove(id: String)
}

internal class ImportCleanup(private val db: FolioDatabase, private val originals: ImportedOriginals) {
    private val queue = db.importCleanupDao()

    // 文件复制在 Room 写事务外运行。进程终止后，这些 ID 自动失去保护，持久队列仍在。
    private companion object {
        val copying = ConcurrentHashMap.newKeySet<String>()
    }

    suspend fun <T> importing(type: ImportType, copyAndBind: suspend (UUID) -> T): T {
        val id = UUID.randomUUID()
        val key = id.toString()
        copying.add(key)
        try {
            queue.reserve(ImportCleanupEntry(key, type))
            return copyAndBind(id)
        } catch (failure: Throwable) {
            copying.remove(key)
            // 即使调用方已取消，仍清理本次独占文件。绑定已提交时，队列项已随事务移除。
            withContext(NonCancellable) {
                try { clean(key) } catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            }
            throw failure
        } finally {
            copying.remove(key)
        }
    }

    suspend fun run(): Int {
        queue.candidates().forEach { clean(it) }
        return queue.candidates().count { it !in copying }
    }

    private suspend fun clean(id: String) = db.withTransaction {
        val entry = queue.entry(id) ?: return@withTransaction
        if (id in copying) return@withTransaction
        val referenced = when (entry.type) {
            ImportType.PDF -> queue.pdfReferenced(id)
            ImportType.AUDIO -> db.audioCleanupDao().isReferenced(id)
            ImportType.VIDEO -> queue.videoReferenced(id)
        }
        try {
            if (!referenced) originals.delete(UUID.fromString(id), entry.type)
            queue.remove(id)
        } catch (_: IOException) {
            // 无引用的原件暂时删不掉时留下队列；已完成的记录操作仍然有效。
        }
    }
}
