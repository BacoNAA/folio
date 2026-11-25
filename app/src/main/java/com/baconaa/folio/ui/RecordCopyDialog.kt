package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*

@Composable
internal fun RecordCopyDialog(content: RecordContent, selection: RecordCopySelection, model: FolioViewModel) {
    val state = model.state
    val enabled = !state.busy
    val sourceItems = remember(content.items) { content.items.withIndex().associateBy { it.value.id } }
    val chosen = remember(selection.itemIds) { selection.itemIds.toSet() }
    fun move(index: Int, to: Int) {
        val ids = selection.itemIds.toMutableList()
        ids.add(to, ids.removeAt(index))
        model.changeRecordCopy(selection.copy(itemIds = ids))
    }
    AlertDialog(onDismissRequest = { model.changeRecordCopy(null) }, title = { Text("复制为新记录") },
        text = { Column {
            if (state.drafts.any { it.recordId == state.detailId }) Text("来源有未完成修改。本次使用上次完成的内容。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("copy-options")) {
                item { Text("选择内容", style = MaterialTheme.typography.titleSmall) }
                items(content.items, key = { "item-" + it.id.value }) { item ->
                    CopyChoice(item.id in chosen, enabled, Modifier.testTag("copy-item-${item.id.value}"), { on ->
                        val ids = selection.itemIds.toMutableList()
                        if (on) {
                            val next = ids.indexOfFirst { sourceItems.getValue(it).index > sourceItems.getValue(item.id).index }
                            ids.add(if (next < 0) ids.size else next, item.id)
                        } else ids.remove(item.id)
                        model.changeRecordCopy(selection.copy(itemIds = ids))
                    }) {
                        if (item is ContentItem.Photo) Photo(item.photoId, thumbnail = true, thumbnailDescription = "待选照片", strokes = item.strokes)
                        Text(item.copyLabel(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (selection.itemIds.isNotEmpty()) item { Text("副本顺序", style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(selection.itemIds, key = { _, id -> "preview-" + id.value }) { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${sourceItems.getValue(id).value.copyLabel()}", Modifier.weight(1f)
                            .testTag("copy-preview-${id.value}"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (selection.itemIds.size > 1) {
                            TextButton(onClick = { move(index, index - 1) }, enabled = enabled && index > 0,
                                modifier = Modifier.testTag("copy-up-${id.value}")) { Text("上移") }
                            TextButton(onClick = { move(index, index + 1) }, enabled = enabled && index < selection.itemIds.lastIndex,
                                modifier = Modifier.testTag("copy-down-${id.value}")) { Text("下移") }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    content.title?.let { title ->
                        CopyChoice(selection.keepTitle, enabled, change = { model.changeRecordCopy(selection.copy(keepTitle = it)) }) {
                            Text("沿用标题「$title」")
                        }
                    }
                    content.times.eventTime?.let { time ->
                        CopyChoice(selection.keepEventTime, enabled, change = { model.changeRecordCopy(selection.copy(keepEventTime = it)) }) {
                            Text("沿用事件时间 · ${time.label}")
                        }
                    }
                    if (selection.itemIds.any { sourceItems.getValue(it).value is ContentItem.Checklist }) {
                        CopyChoice(selection.keepChecked, enabled, change = { model.changeRecordCopy(selection.copy(keepChecked = it)) }) {
                            Text("沿用清单勾选状态")
                        }
                    }
                    content.place?.let { place ->
                        CopyChoice(selection.placeId == place.id, enabled, change = { on ->
                            model.changeRecordCopy(selection.copy(placeId = place.id.takeIf { on }))
                        }) {
                            Text("地点", style = MaterialTheme.typography.labelSmall)
                            PlaceDescription(place)
                        }
                    }
                }
                items(content.tags, key = { "tag-" + it.id.value }) { tag ->
                    CopyChoice(tag.id in selection.tagIds, enabled, change = { on ->
                        model.changeRecordCopy(selection.copy(tagIds = if (on) selection.tagIds + tag.id else selection.tagIds - tag.id))
                    }) { Text("标签 · ${tag.name}") }
                }
                items(content.objects, key = { "object-" + it.id.value }) { obj ->
                    CopyChoice(obj.id in selection.objectIds, enabled, change = { on ->
                        model.changeRecordCopy(selection.copy(objectIds = if (on) selection.objectIds + obj.id else selection.objectIds - obj.id))
                    }) {
                        Text(obj.kind.label, style = MaterialTheme.typography.labelSmall)
                        ObjectDescription(obj)
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = model::createRecordCopy, enabled = enabled && selection.itemIds.isNotEmpty()) {
            Text(if (state.error == null) "创建副本草稿" else "重试创建")
        } },
        dismissButton = { TextButton(onClick = { model.changeRecordCopy(null) }, enabled = enabled) { Text("取消") } })
}

@Composable
private fun CopyChoice(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, change: (Boolean) -> Unit,
    label: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(start = 10.dp)) { label() }
    }
}

private fun ContentItem.copyLabel(): String = when (this) {
    is ContentItem.Text -> "文字 · $text"
    is ContentItem.Checklist -> "清单 · " + entries.take(3).joinToString("、") { it.text }.let {
        if (entries.size > 3) "$it 等 ${entries.size} 项" else it
    }
    is ContentItem.Photo -> caption.ifBlank { "照片" }
    is ContentItem.Audio -> "录音 · ${formatAudioDuration(durationMillis)} $caption".trimEnd()
    is ContentItem.Pdf -> "PDF · $displayName"
    is ContentItem.Video -> "视频 · $displayName"
    is ContentItem.Link -> "网址 · ${title.ifBlank { address }}"
}
