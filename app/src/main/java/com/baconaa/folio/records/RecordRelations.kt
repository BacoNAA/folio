package com.baconaa.folio.records

import androidx.room.*
import androidx.room.withTransaction

@Entity(tableName = "record_relations", primaryKeys = ["firstId", "secondId"],
    foreignKeys = [
        ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["firstId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["secondId"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("secondId")])
internal data class RelationRow(val firstId: String, val secondId: String)

@Dao
internal interface RelationDao {
    @Query("""SELECT CASE WHEN firstId=:id THEN secondId ELSE firstId END
        FROM record_relations JOIN text_records a ON a.id=firstId JOIN text_records b ON b.id=secondId
        WHERE (firstId=:id OR secondId=:id) AND a.deletedAt IS NULL AND b.deletedAt IS NULL""")
    suspend fun related(id: String): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(row: RelationRow)
    @Delete suspend fun remove(row: RelationRow): Int
}

data class RelationChoices(val records: List<RecordCard>, val related: Set<RecordId>)
interface RecordRelations {
    suspend fun relationChoices(source: RecordId): RelationChoices
    suspend fun changeRelation(source: RecordId, previous: RecordId?, next: RecordId?)
}

internal class LocalRecordRelations(private val db: FolioDatabase) : RecordRelations {
    private val dao = db.relationDao()
    private val browsing = LocalRecordBrowsing(db)
    private fun pair(a: RecordId, b: RecordId) =
        if (a.value < b.value) RelationRow(a.value, b.value) else RelationRow(b.value, a.value)

    override suspend fun relationChoices(source: RecordId) = db.withTransaction {
        require(db.textDao().activeRecord(source.value) != null) { "来源记录已不可用。" }
        RelationChoices(browsing.listRecentRecords().filter { it.id != source }, dao.related(source.value).map(::RecordId).toSet())
    }

    override suspend fun changeRelation(source: RecordId, previous: RecordId?, next: RecordId?) = db.withTransaction {
        require(previous != null || next != null)
        require(source != next && source != previous) { "请选择另一条正式记录。" }
        require(db.textDao().activeRecord(source.value) != null) { "来源记录已不可用。" }
        if (next != null) require(db.textDao().activeRecord(next.value) != null) { "所选记录已不可用，请重选。" }
        if (previous == next) return@withTransaction
        if (previous != null && dao.remove(pair(source, previous)) == 0) {
            require(next == null || next.value in dao.related(source.value)) { "原关联已变更，请重新选择。" }
            return@withTransaction
        }
        if (next != null) dao.add(pair(source, next))
    }
}
