package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns

internal data class BrowseRecordRow(
    val id: String, @Embedded val eventTime: EventTimeFields?, @Embedded val marks: RecordMarks,
)
internal data class PreviewItemRow(val recordId: String, @Embedded val slot: ContentSlot)
internal data class PreviewCountRow(val recordId: String, val itemCount: Int, val photoCount: Int, val audioCount: Int)

private const val VISIBLE_PREVIEW_ITEM = "(photoId IS NOT NULL OR audioId IS NOT NULL OR checklistJson IS NOT NULL OR length(trim(text, :blankChars)) > 0)"

@Dao
internal interface RecordBrowsingDao {
    @RewriteQueriesToDropUnusedColumns
    @Query("""SELECT r.* FROM text_records r
              WHERE deletedAt IS NULL AND (:starredOnly = 0 OR isStarred = 1)
              AND (:allTags = 1 OR EXISTS(SELECT 1 FROM record_tags rt WHERE rt.recordId = r.id AND rt.tagId IN (:tagIds)))
              AND (:placeId IS NULL OR EXISTS(SELECT 1 FROM record_places rp WHERE rp.recordId = r.id AND rp.placeId = :placeId))
              AND (:allPeople = 1 OR EXISTS(SELECT 1 FROM published_objects po JOIN record_objects o ON o.id = po.objectId
                  WHERE po.recordId = r.id AND o.kind = 'PERSON' AND o.id IN (:peopleIds)))
              AND (:allThings = 1 OR EXISTS(SELECT 1 FROM published_objects po JOIN record_objects o ON o.id = po.objectId
                  WHERE po.recordId = r.id AND o.kind = 'THING' AND o.id IN (:thingIds)))
              AND (:allMedia OR
                  (:photo AND EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND i.photoId IS NOT NULL)) OR
                  (:audio AND EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND i.audioId IS NOT NULL)) OR
                  (:checklist AND EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND i.checklistJson IS NOT NULL)) OR
                  (:textOnly AND EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND i.text IS NOT NULL)
                      AND NOT EXISTS(SELECT 1 FROM record_items i WHERE i.recordId = r.id AND i.text IS NULL)))
              AND """ + RECORD_TEXT_MATCH + " ORDER BY isPinned DESC, firstRecordedAt DESC, r.rowid DESC")
    suspend fun activeRecords(keyword: String, tagIds: List<String>, allTags: Boolean, starredOnly: Boolean, placeId: String?,
        peopleIds: List<String>, allPeople: Boolean, thingIds: List<String>, allThings: Boolean,
        allMedia: Boolean, photo: Boolean, audio: Boolean, checklist: Boolean, textOnly: Boolean): List<BrowseRecordRow>

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
