package com.baconaa.folio.records

import androidx.room.*
import androidx.room.withTransaction
import java.time.LocalDate

enum class StateTargetKind(val label: String) { PERSON("人物"), THING("物件"), PLACE("地点"), RECORD("记录") }

data class StateTarget(val kind: StateTargetKind, val id: String) {
    constructor(value: RecordObject) : this(StateTargetKind.valueOf(value.kind.name), value.id.value)
    constructor(value: Place) : this(StateTargetKind.PLACE, value.id.value)
    constructor(id: RecordId) : this(StateTargetKind.RECORD, id.value)
    internal val key get() = "${kind.name}:$id"
}

@Entity(tableName = "custom_states")
data class CustomState(@PrimaryKey val id: String, val name: String, val pattern: String) {
    val label get() = "$pattern $name"
}

data class StateNote(val date: LocalDate, val note: String)
data class StateChange(val previous: CustomState?, val next: CustomState, val entry: StateNote)
data class TargetStates(val choices: List<CustomState>, val current: CustomState?, val history: List<StateChange>)
data class StateMatch(val kind: StateTargetKind, val id: String, val name: String, val detail: String)

interface RecordStates {
    suspend fun defineState(state: CustomState)
    suspend fun listStates(): List<CustomState>
    suspend fun currentState(target: StateTarget): CustomState?
    suspend fun readStates(target: StateTarget): TargetStates
    suspend fun setState(target: StateTarget, expected: String?, next: String, request: String, remember: StateNote?)
    suspend fun filterState(stateId: String): List<StateMatch>
}

@Entity(tableName = "target_states", foreignKeys = [
    ForeignKey(entity = CustomState::class, parentColumns = ["id"], childColumns = ["stateId"]),
    ForeignKey(entity = RecordRow::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlaceRow::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ObjectRow::class, parentColumns = ["id"], childColumns = ["objectId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("stateId"), Index(value = ["recordId"], unique = true), Index(value = ["placeId"], unique = true),
    Index(value = ["objectId"], unique = true)])
internal data class TargetStateRow(@PrimaryKey val targetKey: String, val stateId: String,
    val recordId: String?, val placeId: String?, val objectId: String?)

@Entity(tableName = "state_changes", foreignKeys = [
    ForeignKey(entity = TargetStateRow::class, parentColumns = ["targetKey"], childColumns = ["targetKey"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = CustomState::class, parentColumns = ["id"], childColumns = ["previous"]),
    ForeignKey(entity = CustomState::class, parentColumns = ["id"], childColumns = ["next"]),
], indices = [Index("targetKey"), Index("previous"), Index("next")])
internal data class StateChangeRow(@PrimaryKey val request: String, val targetKey: String,
    val previous: String?, val next: String, val day: Long, val note: String)

@Dao
internal interface CustomStateDao {
    @Query("SELECT * FROM custom_states ORDER BY name COLLATE NOCASE, pattern, id")
    suspend fun states(): List<CustomState>
    @Query("SELECT * FROM custom_states WHERE id = :id") suspend fun state(id: String): CustomState?
    @Insert suspend fun addState(state: CustomState)
    @Query("SELECT stateId FROM target_states WHERE targetKey = :key") suspend fun current(key: String): String?
    @Query("SELECT c.* FROM custom_states c JOIN target_states t ON t.stateId = c.id WHERE t.targetKey = :key")
    suspend fun currentState(key: String): CustomState?
    @Upsert suspend fun set(row: TargetStateRow)
    @Insert suspend fun addChange(row: StateChangeRow)
    @Query("SELECT * FROM state_changes WHERE targetKey = :key ORDER BY day DESC, rowid DESC")
    suspend fun changes(key: String): List<StateChangeRow>
    @Query("SELECT * FROM state_changes WHERE request = :request") suspend fun receipt(request: String): StateChangeRow?

    @Query("""SELECT o.kind, o.id, o.name, o.note AS detail FROM target_states s JOIN record_objects o ON o.id = s.objectId
        WHERE s.stateId = :id AND o.ownerDraftId IS NULL
        UNION ALL SELECT 'PLACE', p.id, p.name, trim(p.city || ' ' || p.address) FROM target_states s JOIN places p ON p.id = s.placeId
        WHERE s.stateId = :id AND p.ownerDraftId IS NULL
        UNION ALL SELECT 'RECORD', r.id, COALESCE(nullif(r.title, ''), nullif(substr(r.body, 1, 80), ''), '无标题记录'), ''
        FROM target_states s JOIN text_records r ON r.id = s.recordId WHERE s.stateId = :id AND r.deletedAt IS NULL
        ORDER BY kind, name, id""")
    suspend fun matches(id: String): List<StateMatch>

    @Query("""SELECT EXISTS(SELECT 1 FROM target_states s LEFT JOIN record_objects o ON o.id = s.objectId
        LEFT JOIN places p ON p.id = s.placeId
        WHERE (s.recordId IS NOT NULL) + (s.placeId IS NOT NULL) + (s.objectId IS NOT NULL) != 1
        OR s.targetKey != CASE WHEN s.recordId IS NOT NULL THEN 'RECORD:' || s.recordId
                              WHEN s.placeId IS NOT NULL THEN 'PLACE:' || s.placeId ELSE o.kind || ':' || o.id END
        OR o.ownerDraftId IS NOT NULL OR p.ownerDraftId IS NOT NULL)""")
    suspend fun invalidTarget(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM state_changes WHERE day NOT BETWEEN :first AND :last OR previous = next)")
    suspend fun invalidChange(first: Long, last: Long): Boolean
}

internal suspend fun validateCustomStates(dao: CustomStateDao) {
    require(dao.states().all { it.id.isNotBlank() && it.name.isNotBlank() && it.pattern.isNotBlank() } &&
        !dao.invalidTarget() && !dao.invalidChange(LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay())) {
        "自定义状态或已保存的变化无效。"
    }
}

internal class LocalRecordStates(private val db: FolioDatabase) : RecordStates {
    private val dao = db.customStateDao()

    override suspend fun defineState(state: CustomState) = db.withTransaction {
        val clean = state.copy(name = state.name.trim(), pattern = state.pattern.trim())
        require(clean.name.isNotEmpty() && clean.pattern.isNotEmpty() && clean.id.isNotBlank()) { "请填写状态名称和图案。" }
        val saved = dao.state(clean.id)
        if (saved == null) dao.addState(clean) else require(saved == clean) { "此状态身份已经使用。" }
    }

    override suspend fun listStates() = dao.states()
    override suspend fun currentState(target: StateTarget) = dao.currentState(target.key)

    private suspend fun requireTarget(target: StateTarget) {
        val available = when (target.kind) {
            StateTargetKind.RECORD -> db.textDao().activeRecord(target.id) != null
            StateTargetKind.PLACE -> db.placeDao().place(target.id)?.let { it.ownerDraftId == null } == true
            StateTargetKind.PERSON, StateTargetKind.THING -> db.objectDao().objectById(target.id)?.row?.let {
                it.ownerDraftId == null && it.kind == target.kind.name
            } == true
        }
        require(available) { "这项资料已不可用。" }
    }

    override suspend fun readStates(target: StateTarget) = db.withTransaction {
        requireTarget(target)
        val choices = dao.states()
        val byId = choices.associateBy { it.id }
        val history = dao.changes(target.key).map {
            StateChange(it.previous?.let(byId::getValue), byId.getValue(it.next), StateNote(LocalDate.ofEpochDay(it.day), it.note))
        }
        TargetStates(choices, dao.current(target.key)?.let(byId::getValue), history)
    }

    override suspend fun setState(target: StateTarget, expected: String?, next: String, request: String, remember: StateNote?) {
        db.withTransaction {
            requireTarget(target)
            require(dao.state(next) != null) { "所选状态已不可用。" }
            val change = remember?.let {
                require(request.isNotBlank())
                StateChangeRow(request, target.key, expected, next, it.date.toEpochDay(), it.note.trim())
            }
            if (change != null) dao.receipt(request)?.let {
                require(it == change) { "这次提交已经用于另一项变化。" }
                return@withTransaction
            }
            val current = dao.current(target.key)
            if (current == next && change == null) return@withTransaction
            require(current == expected) { "当前状态已经改变，请重新打开后选择。" }
            require(current != next) { "请先选择不同的状态。" }
            dao.set(TargetStateRow(target.key, next,
                target.id.takeIf { target.kind == StateTargetKind.RECORD },
                target.id.takeIf { target.kind == StateTargetKind.PLACE },
                target.id.takeIf { target.kind == StateTargetKind.THING || target.kind == StateTargetKind.PERSON }))
            if (change != null) dao.addChange(change)
        }
    }

    override suspend fun filterState(stateId: String) = dao.matches(stateId)
}
