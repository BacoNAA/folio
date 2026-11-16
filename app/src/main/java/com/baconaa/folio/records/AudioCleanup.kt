package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.media.LocalAudioOriginals
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Entity(tableName = "audio_cleanup_queue")
internal data class AudioCleanupEntry(@PrimaryKey val audioId: String)

@Dao
internal interface AudioCleanupDao {
    @Query("""INSERT OR IGNORE INTO audio_cleanup_queue(audioId)
              SELECT audioId FROM draft_items WHERE draftId = :id AND audioId IS NOT NULL
              UNION SELECT audioId FROM removed_draft_items WHERE draftId = :id AND audioId IS NOT NULL""")
    suspend fun enqueueDraft(id: String)

    @Query("""INSERT OR IGNORE INTO audio_cleanup_queue(audioId)
              SELECT audioId FROM record_items WHERE recordId = :id AND audioId IS NOT NULL""")
    suspend fun enqueueRecord(id: String)

    @Query("SELECT audioId FROM audio_cleanup_queue ORDER BY audioId")
    suspend fun candidates(): List<String>

    // 回收站、隐藏草稿及可撤销移除与有效正式内容一样持有原件。
    @Query("""SELECT EXISTS(SELECT 1 FROM record_items WHERE audioId = :id
              UNION ALL SELECT 1 FROM draft_items WHERE audioId = :id
              UNION ALL SELECT 1 FROM removed_draft_items WHERE audioId = :id)""")
    suspend fun isReferenced(id: String): Boolean

    @Query("DELETE FROM audio_cleanup_queue WHERE audioId = :id")
    suspend fun remove(id: String)

    @Query("SELECT COUNT(*) FROM audio_cleanup_queue")
    suspend fun pendingCount(): Int
}

internal class AudioCleanup(
    private val database: FolioDatabase,
    private val originals: LocalAudioOriginals,
) {
    private val queue = database.audioCleanupDao()

    /** 只在业务提交后运行；引用检查与文件删除之间不能插入新引用。 */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        for (id in queue.candidates()) {
            ensureActive()
            database.withTransaction {
                if (queue.isReferenced(id)) {
                    queue.remove(id)
                } else {
                    try {
                        originals.deleteOriginal(AudioId(UUID.fromString(id)))
                        queue.remove(id)
                    } catch (_: IOException) {
                        // 文件失败不撤销已完成的业务操作，保留队列供重开后重试。
                    }
                }
            }
        }
        queue.pendingCount()
    }
}
