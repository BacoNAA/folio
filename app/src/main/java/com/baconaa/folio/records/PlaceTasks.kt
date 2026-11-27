package com.baconaa.folio.records

import androidx.room.*
import java.util.UUID

@Entity(tableName = "place_tasks", foreignKeys = [
    ForeignKey(entity = PlaceRow::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("placeId")])
internal data class PlaceTaskRow(@PrimaryKey val id: String, val placeId: String, val label: String, val checked: Boolean)

@Entity(tableName = "task_experiences", primaryKeys = ["taskId", "recordId"], foreignKeys = [
    ForeignKey(entity = PlaceTaskRow::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("recordId")])
internal data class TaskExperience(val taskId: String, val recordId: String)

@Entity(tableName = "task_experience_drafts", foreignKeys = [
    ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlaceTaskRow::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("taskId")])
internal data class TaskDraft(@PrimaryKey val draftId: String, val taskId: String)

internal data class ExperienceChoice(val id: String, val label: String)
internal data class PlaceTask(val row: PlaceTaskRow, val experiences: List<ExperienceChoice>)

private const val EXPERIENCE_LABEL = "r.id, COALESCE(NULLIF(r.title,''), NULLIF(substr(r.body,1,80),''), '体验记录') AS label"

@Dao
internal interface PlaceTaskDao {
    @Query("SELECT * FROM place_tasks WHERE placeId=:place ORDER BY rowid")
    suspend fun tasks(place: String): List<PlaceTaskRow>
    @Query("SELECT * FROM place_tasks WHERE id=:id")
    suspend fun task(id: String): PlaceTaskRow?
    @Insert suspend fun insert(task: PlaceTaskRow)
    @Query("UPDATE place_tasks SET checked=:checked WHERE id=:id")
    suspend fun checkTask(id: String, checked: Boolean): Int
    @Query("SELECT " + EXPERIENCE_LABEL + " FROM text_records r JOIN task_experiences x ON x.recordId=r.id WHERE x.taskId=:task AND r.deletedAt IS NULL ORDER BY r.firstRecordedAt DESC,r.id")
    suspend fun experiences(task: String): List<ExperienceChoice>
    @Query("SELECT " + EXPERIENCE_LABEL + " FROM text_records r JOIN record_places p ON p.recordId=r.id WHERE p.placeId=:place AND r.deletedAt IS NULL ORDER BY r.firstRecordedAt DESC,r.id")
    suspend fun candidates(place: String): List<ExperienceChoice>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(value: TaskExperience)
    @Insert suspend fun bind(value: TaskDraft)
    @Query("SELECT t.* FROM place_tasks t JOIN task_experience_drafts d ON d.taskId=t.id WHERE d.draftId=:draft")
    suspend fun pending(draft: String): PlaceTaskRow?
}

internal class PlaceTasks(private val db: FolioDatabase) {
    private val dao = db.placeTaskDao()
    suspend fun read(place: PlaceId) = db.withTransaction {
        dao.tasks(place.value).map { PlaceTask(it, dao.experiences(it.id)) }
    }
    suspend fun add(place: PlaceId, label: String) = db.withTransaction {
        require(label.isNotBlank()) { "请填写下次想做的事。" }
        require(db.placeDao().place(place.value)?.let { it.ownerDraftId == null } == true) { "地点已不可用。" }
        val id = UUID.randomUUID().toString()
        dao.insert(PlaceTaskRow(id, place.value, label.trim(), false))
        id
    }
    suspend fun check(id: String, checked: Boolean) {
        check(dao.checkTask(id, checked) == 1) { "事项已不可用。" }
    }
    suspend fun candidates(place: PlaceId) = dao.candidates(place.value)
    suspend fun link(task: String, record: RecordId) = db.withTransaction {
        val row = requireNotNull(dao.task(task)) { "事项已不可用。" }
        require(db.textDao().activeRecord(record.value) != null && db.placeDao().recordPlace(record.value)?.id == row.placeId) {
            "请选择这个地点仍可打开的体验。"
        }
        dao.link(TaskExperience(row.id, record.value))
    }
}
