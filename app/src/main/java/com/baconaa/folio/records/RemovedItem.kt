package com.baconaa.folio.records

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "removed_draft_items",
    foreignKeys = [ForeignKey(
        entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class RemovedItem(
    @PrimaryKey val draftId: String,
    @Embedded val slot: ContentSlot,
    val position: Int,
    val previousItemId: String?,
    val nextItemId: String?,
    val cursor: Int,
)

@Dao
internal interface RemovedItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: RemovedItem)

    @Query("SELECT * FROM removed_draft_items WHERE draftId = :id")
    suspend fun get(id: String): RemovedItem?

    @Query("""SELECT EXISTS(SELECT 1 FROM removed_draft_items r
              JOIN editable_text_drafts d ON d.id = r.draftId WHERE r.draftId = :id)""")
    suspend fun available(id: String): Boolean

    @Query("DELETE FROM removed_draft_items WHERE draftId = :id")
    suspend fun clear(id: String)
}
