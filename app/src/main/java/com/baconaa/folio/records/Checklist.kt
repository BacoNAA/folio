package com.baconaa.folio.records

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

@JvmInline
value class ChecklistEntryId(val value: String) {
    init { require(value.isNotBlank()) { "清单事项 ID 不能为空" } }

    companion object {
        fun create() = ChecklistEntryId(UUID.randomUUID().toString())
    }
}

data class ChecklistEntry(val id: ChecklistEntryId, val text: String, val checked: Boolean = false)

internal fun List<ChecklistEntry>.requireUniqueEntryIds() {
    require(map { it.id }.distinct().size == size) { "同一清单不能包含重复的事项 ID" }
}

internal fun encodeChecklist(entries: List<ChecklistEntry>): String {
    entries.requireUniqueEntryIds()
    return JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("id", entry.id.value).put("text", entry.text).put("checked", entry.checked))
        }
    }.toString()
}

internal fun decodeChecklist(json: String): List<ChecklistEntry> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val entry = array.getJSONObject(index)
        // 不把损坏的保存值转换为空清单或默认未完成，以免重存时覆盖真实内容。
        val id = entry.get("id")
        val text = entry.get("text")
        val checked = entry.get("checked")
        check(id is String && text is String && checked is Boolean) { "清单事项数据无效" }
        ChecklistEntry(ChecklistEntryId(id), text, checked)
    }.also { it.requireUniqueEntryIds() }
}
