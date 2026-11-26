package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.baconaa.folio.media.PhotoId
import java.util.UUID

data class ChapterCover(val record: RecordId, val item: ItemId)
data class ChapterPhoto(val cover: ChapterCover, val photo: PhotoId, val label: String)
data class ChapterProfile(val name: String, val start: String = "", val end: String = "", val note: String = "",
    val cover: ChapterCover? = null) {
    fun inputError(): String? {
        if (name.isBlank()) return "请填写章节名称。"
        val from = EventDateFilter.Range.parse(start)
        val to = EventDateFilter.Range.parse(end)
        if ((start.isNotBlank() && from == null) || (end.isNotBlank() && to == null))
            return "起止可留空，或填写年、年-月、年-月-日。"
        if (from != null && to != null && to.end < from.start) return "结束时间不能早于开始时间。"
        return null
    }
}

@Entity(tableName = "life_chapters", foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"],
    childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
internal data class ChapterRow(@PrimaryKey val groupId: String, val start: String, val end: String, val note: String,
    val coverRecord: String?, val coverItem: String?) {
    fun profile(name: String) = ChapterProfile(name, start, end, note,
        if (coverRecord != null && coverItem != null) ChapterCover(RecordId(coverRecord), ItemId(coverItem)) else null)
}

internal data class ChapterPhotoRow(val recordId: String, val itemId: String, val photoId: String, val label: String) {
    fun photo() = ChapterPhoto(ChapterCover(RecordId(recordId), ItemId(itemId)), PhotoId(UUID.fromString(photoId)), label)
}

private const val CHAPTER_PHOTOS = """SELECT i.recordId, i.itemId, i.photoId,
    COALESCE(NULLIF(i.caption,''), NULLIF(r.title,''), NULLIF(substr(r.body,1,40),''),'照片') AS label
    FROM record_items i JOIN text_records r ON r.id=i.recordId WHERE r.deletedAt IS NULL AND i.photoId IS NOT NULL"""

@Dao
internal interface ChapterDao {
    @Query("SELECT g.* FROM record_groups g JOIN life_chapters c ON c.groupId=g.id WHERE g.name IS NOT NULL ORDER BY g.rowid DESC")
    suspend fun list(): List<GroupRow>
    @Query("SELECT * FROM life_chapters WHERE groupId=:id")
    suspend fun chapter(id: String): ChapterRow?
    @Upsert suspend fun put(row: ChapterRow)
    @Query("UPDATE record_groups SET name=:name WHERE id=:id AND name IS NOT NULL")
    suspend fun rename(id: String, name: String)
    @Query(CHAPTER_PHOTOS + " ORDER BY r.firstRecordedAt DESC, r.rowid DESC, i.position")
    suspend fun photos(): List<ChapterPhotoRow>
    @Query(CHAPTER_PHOTOS + " AND i.recordId=:record AND i.itemId=:item")
    suspend fun photo(record: String, item: String): ChapterPhotoRow?
    @Query("DELETE FROM life_chapters WHERE groupId=:id")
    suspend fun remove(id: String)
}

interface RecordChapters {
    suspend fun listChapters(): List<RecordGroup>
    suspend fun chapterPhotos(): List<ChapterPhoto>
    suspend fun saveChapter(id: RecordGroupId, expected: ChapterProfile?, value: ChapterProfile)
}

internal class LocalRecordChapters(private val db: FolioDatabase) : RecordChapters {
    private val dao = db.chapterDao()
    override suspend fun listChapters() = dao.list().map { RecordGroup(RecordGroupId(it.id), checkNotNull(it.name)) }
    override suspend fun chapterPhotos() = dao.photos().map { it.photo() }
    override suspend fun saveChapter(id: RecordGroupId, expected: ChapterProfile?, value: ChapterProfile) = db.withTransaction {
        value.inputError()?.let { throw IllegalArgumentException(it) }
        val existing = db.groupDao().group(id.value)
        val current = existing?.name?.let { dao.chapter(id.value)?.profile(it) }
        if (current == value) return@withTransaction
        require(current == expected && (existing == null || current != null)) { "章节已变化，请重开后再编辑。" }
        value.cover?.takeIf { it != current?.cover }?.let {
            require(dao.photo(it.record.value, it.item.value) != null) { "所选照片已不可用，请重新选封面。" }
        }
        if (existing == null) db.groupDao().insertGroup(GroupRow(id.value, value.name)) else dao.rename(id.value, value.name)
        dao.put(ChapterRow(id.value, value.start, value.end, value.note, value.cover?.record?.value, value.cover?.item?.value))
    }
}
