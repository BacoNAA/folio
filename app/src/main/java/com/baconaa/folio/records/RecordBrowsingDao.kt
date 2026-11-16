package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

internal data class BrowseRecordRow(val id: String, @Embedded val eventTime: EventTimeFields?)
internal data class PreviewItemRow(val recordId: String, @Embedded val slot: ContentSlot)
internal data class PreviewCountRow(val recordId: String, val itemCount: Int, val photoCount: Int, val audioCount: Int)

private const val VISIBLE_PREVIEW_ITEM = "(photoId IS NOT NULL OR audioId IS NOT NULL OR checklistJson IS NOT NULL OR length(trim(text, :blankChars)) > 0)"

@Dao
internal interface RecordBrowsingDao {
    @Query("""SELECT id, eventDate, eventClock, eventZone FROM text_records
              WHERE deletedAt IS NULL ORDER BY firstRecordedAt DESC, rowid DESC""")
    suspend fun activeRecords(): List<BrowseRecordRow>

    // 每条最多读三项的短预览，不加载所有正式正文，也不逐条发起数据库查询。
    @Query("""SELECT i.recordId, i.itemId, substr(i.text, 1, 80) AS text,
              i.photoId, substr(i.caption, 1, 80) AS caption, i.checklistJson, i.audioId, i.audioDurationMillis
              FROM record_items i JOIN text_records r ON r.id = i.recordId
              WHERE r.deletedAt IS NULL AND i.position IN
                (SELECT position FROM record_items WHERE recordId = i.recordId AND """ + VISIBLE_PREVIEW_ITEM + """
                 ORDER BY position LIMIT 3)
              ORDER BY i.recordId, i.position""")
    suspend fun previewItems(blankChars: String): List<PreviewItemRow>

    @Query("""SELECT recordId, count(*) AS itemCount, count(photoId) AS photoCount, count(audioId) AS audioCount
              FROM record_items JOIN text_records r ON r.id = recordId
              WHERE r.deletedAt IS NULL AND """ + VISIBLE_PREVIEW_ITEM + " GROUP BY recordId")
    suspend fun previewCounts(blankChars: String): List<PreviewCountRow>
}
