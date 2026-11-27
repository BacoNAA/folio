package com.baconaa.folio.records

import com.baconaa.folio.media.StampArtwork
import androidx.room.withTransaction
import java.util.UUID

@JvmInline
value class TagId(val value: String)

data class Tag(val id: TagId, val name: String, val stampRevision: String? = null)

/** 标签身份独立于名称；同名标签不会自动合并。 */
interface RecordTags {
    suspend fun saveStamp(draft: DraftId?, tag: TagId?, name: String, png: ByteArray): TagId
    suspend fun readStamp(tag: Tag): ByteArray?
    /** 只列正式共享标签，包括暂时没有关联记录的标签。 */
    suspend fun listTags(keyword: String = ""): List<Tag>
    /** 创建本草稿私有标签并选中，完成后其他草稿才可复用。 */
    suspend fun createDraftTag(id: DraftId, name: String): TagId
    suspend fun renameDraftTag(id: DraftId, tagId: TagId, name: String)
    /** 按给定顺序保存选择；只接受共享标签或本草稿私有标签，空列表清除选择。 */
    suspend fun saveDraftTags(id: DraftId, tagIds: List<TagId>)
    /** 草稿不存在或在回收站中时返回 null；名称读取共享标签的当前值。 */
    suspend fun openDraftTags(id: DraftId): List<Tag>?
    /** 更名共享标签，所有引用同步显示；不改变记录内容修改时间。 */
    suspend fun renameTag(id: TagId, name: String)
}

internal class LocalRecordTags(private val database: FolioDatabase, private val now: () -> Long) : RecordTags {
    private val dao = database.tagDao()
    private val drafts = database.textDao()

    override suspend fun listTags(keyword: String): List<Tag> = dao.sharedTags(keyword).map { it.asTag() }

    override suspend fun createDraftTag(id: DraftId, name: String): TagId = database.withTransaction {
        requireDraft(id)
        val tag = TagRow(UUID.randomUUID().toString(), tagName(name), id.value)
        dao.insertTag(tag)
        dao.replaceDraftTags(id.value, dao.draftTags(id.value).map { it.id } + tag.id)
        drafts.touchDraft(id.value, now())
        TagId(tag.id)
    }

    override suspend fun renameDraftTag(id: DraftId, tagId: TagId, name: String) = database.withTransaction {
        requireDraft(id)
        val tag = checkNotNull(dao.tag(tagId.value)) { "标签已不存在" }
        require(tag.ownerDraftId == id.value) { "只能在本草稿中修改尚未共享的标签" }
        val changed = tagName(name)
        if (tag.name != changed) {
            dao.rename(tag.id, changed)
            drafts.touchDraft(id.value, now())
        }
    }

    override suspend fun saveDraftTags(id: DraftId, tagIds: List<TagId>) = database.withTransaction {
        requireDraft(id)
        require(tagIds.distinct().size == tagIds.size) { "同一标签不能重复选择" }
        val ids = tagIds.map { it.value }
        val tags = dao.tags(ids)
        require(tags.size == ids.size && tags.all { it.ownerDraftId == null || it.ownerDraftId == id.value }) {
            "标签已不存在或尚未在其他草稿中完成，请重新选择"
        }
        if (ids != dao.draftTags(id.value).map { it.id }) {
            dao.replaceDraftTags(id.value, ids)
            drafts.touchDraft(id.value, now())
        }
    }

    override suspend fun openDraftTags(id: DraftId): List<Tag>? = database.withTransaction {
        if (drafts.draft(id.value) == null) null else dao.draftTags(id.value).map { it.asTag() }
    }

    override suspend fun readStamp(tag: Tag) = tag.stampRevision?.let { dao.stamp(tag.id.value, it) }

    override suspend fun saveStamp(draft: DraftId?, tag: TagId?, name: String, png: ByteArray): TagId {
        StampArtwork.decode(png).recycle()
        return database.withTransaction {
            val title = tagName(name)
            if (draft != null) requireDraft(draft)
            val id = tag ?: createDraftTag(requireNotNull(draft) { "请先建立草稿。" }, title)
            if (tag != null) {
                val row = requireNotNull(dao.tag(id.value)) { "标签已不存在。" }
                require(row.ownerDraftId == null || row.ownerDraftId == draft?.value) { "这个标签属于另一份草稿。" }
                if (row.name != title) dao.rename(id.value, title)
                if (row.ownerDraftId != null) drafts.touchDraft(row.ownerDraftId, now())
            }
            dao.putStamp(StampRow(id.value, UUID.randomUUID().toString(), png))
            id
        }
    }

    override suspend fun renameTag(id: TagId, name: String) = database.withTransaction {
        val tag = checkNotNull(dao.tag(id.value)) { "标签已不存在" }
        require(tag.ownerDraftId == null) { "尚未共享的标签请在所属草稿中修改" }
        val changed = tagName(name)
        if (tag.name != changed) dao.rename(tag.id, changed)
    }

    private suspend fun requireDraft(id: DraftId) {
        checkNotNull(drafts.draft(id.value)) { "草稿已不存在或在回收站中，请恢复后再修改标签" }
    }

    private fun tagName(name: String) = name.trim().also { require(it.isNotEmpty()) { "请填写标签名称" } }
}

internal fun TagDisplayRow.asTag() = Tag(TagId(id), name, stampRevision)
