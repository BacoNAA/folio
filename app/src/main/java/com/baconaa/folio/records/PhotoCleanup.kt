package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Entity(tableName = "photo_cleanup_queue")
internal data class PhotoCleanupEntry(@PrimaryKey val photoId: String)

@Dao
internal interface PhotoCleanupDao {
    @Query("""INSERT OR IGNORE INTO photo_cleanup_queue(photoId)
              SELECT photoId FROM draft_items WHERE draftId = :id AND photoId IS NOT NULL
              UNION SELECT photoId FROM removed_draft_items WHERE draftId = :id AND photoId IS NOT NULL""")
    suspend fun enqueueDraft(id: String)

    @Query("""INSERT OR IGNORE INTO photo_cleanup_queue(photoId)
              SELECT photoId FROM record_items WHERE recordId = :id AND photoId IS NOT NULL""")
    suspend fun enqueueRecord(id: String)

    @Query("SELECT photoId FROM photo_cleanup_queue ORDER BY photoId")
    suspend fun candidates(): List<String>

    // 不过滤回收站或隐藏草稿；可撤销项也仍持有原图。
    @Query("""SELECT EXISTS(SELECT 1 FROM record_items WHERE photoId = :id
              UNION ALL SELECT 1 FROM draft_items WHERE photoId = :id
              UNION ALL SELECT 1 FROM removed_draft_items WHERE photoId = :id)""")
    suspend fun isReferenced(id: String): Boolean

    @Query("DELETE FROM photo_cleanup_queue WHERE photoId = :id")
    suspend fun remove(id: String)

    @Query("SELECT COUNT(*) FROM photo_cleanup_queue")
    suspend fun pendingCount(): Int
}

internal class PhotoCleanup(
    private val database: FolioDatabase,
    private val originals: LocalPhotoOriginals,
) {
    private val queue = database.photoCleanupDao()

    /** 业务事务提交后才调用；失败的文件保留在队列，重开数据库后仍可重试。 */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        for (id in queue.candidates()) {
            ensureActive()
            // 引用写入也使用 Room 事务，检查与删文件之间不能插入新引用。
            database.withTransaction {
                if (queue.isReferenced(id)) {
                    queue.remove(id)
                } else {
                    try {
                        originals.deleteOriginal(PhotoId(UUID.fromString(id)))
                        queue.remove(id)
                    } catch (_: IOException) {
                        // 记录删除已成功，文件失败只影响清理，不撤销业务结果。
                    }
                }
            }
        }
        queue.pendingCount()
    }
}
