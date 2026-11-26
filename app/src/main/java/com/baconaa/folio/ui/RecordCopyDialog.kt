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

data class RecordTransfer(val selection: RecordCopySelection, val split: Boolean)

@Composable
internal fun RecordTransferDialog(content: RecordContent, transfer: RecordTransfer, model: FolioViewModel) {
    val selection = transfer.selection
    val split = transfer.split
    val kind = if (split) "split" else "copy"
    val available = remember(content.items, split) { if (split) content.items.filter { it.hasContent() } else content.items }
    val state = model.state
    val enabled = !state.busy
    val sourceItems = remember(content.items) { content.items.withIndex().associateBy { it.value.id } }
    val chosen = remember(selection.itemIds) { selection.itemIds.toSet() }
    val remaining = available.filterNot { it.id in chosen }
    val wouldEmptySource = split && remaining.isEmpty()
    fun move(index: Int, to: Int) {
        val ids = selection.itemIds.toMutableList()
        ids.add(to, ids.removeAt(index))
        model.changeTransfer(selection.copy(itemIds = ids))
    }
    AlertDialog(onDismissRequest = { model.changeTransfer(null) }, title = { Text(if (split) "拆分记录" else "复制为新记录") },
        text = { Column {
            if (wouldEmptySource) {
                Text("原记录须保留至少一项实际内容。请取消勾选一项，或将所选内容复制为新记录。")
                TextButton(onClick = model::switchSplitToCopy, enabled = enabled) { Text("转为复制") }
            }
            if (!split && state.drafts.any { it.recordId == state.detailId }) Text("来源有未完成修改。本次使用上次完成的内容。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("$kind-options")) {
                item { Text(if (split) "选择要移出的内容" else "选择内容", style = MaterialTheme.typography.titleSmall) }
                items(available, key = { "item-" + it.id.value }) { item ->
                    SelectionCheckbox(item.id in chosen, enabled, Modifier.testTag("$kind-item-${item.id.value}"), { on ->
                        val ids = selection.itemIds.toMutableList()
                        if (on) {
                            val next = ids.indexOfFirst { sourceItems.getValue(it).index > sourceItems.getValue(item.id).index }
                            ids.add(if (next < 0) ids.size else next, item.id)
                        } else ids.remove(item.id)
                        model.changeTransfer(selection.copy(itemIds = ids))
                    }) {
                        if (item is ContentItem.Photo) Photo(item.photoId, thumbnail = true, thumbnailDescription = "待选照片", strokes = item.strokes)
                        Text(item.selectionLabel(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (split) {
                    item { Text("原记录将保留", style = MaterialTheme.typography.titleSmall) }
                    items(remaining, key = { "remain-" + it.id.value }) { item ->
                        Text(item.selectionLabel(), Modifier.testTag("split-remain-${item.id.value}"), maxLines = 3,
                            overflow = TextOverflow.Ellipsis)
                    }
                    item {
                        Text("事件时间 · " + (content.times.eventTime?.label ?: "未填写"))
                        val clues = listOfNotNull(content.place?.info?.name) + content.objects.map { it.info.name } + content.tags.map { it.name }
                        if (clues.isNotEmpty()) Text("保留线索 · " + clues.joinToString("、"))
                    }
                }
                if (selection.itemIds.isNotEmpty()) item { Text(if (split) "新记录将得到" else "副本顺序", style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(selection.itemIds, key = { _, id -> "preview-" + id.value }) { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${sourceItems.getValue(id).value.selectionLabel()}", Modifier.weight(1f)
                            .testTag("$kind-preview-${id.value}"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (selection.itemIds.size > 1) {
                            TextButton(onClick = { move(index, index - 1) }, enabled = enabled && index > 0,
                                modifier = Modifier.testTag("$kind-up-${id.value}")) { Text("上移") }
                            TextButton(onClick = { move(index, index + 1) }, enabled = enabled && index < selection.itemIds.lastIndex,
                                modifier = Modifier.testTag("$kind-down-${id.value}")) { Text("下移") }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    if (split) Text("新记录的标题、时间与线索", style = MaterialTheme.typography.titleSmall)
                    content.title?.let { title ->
                        SelectionCheckbox(selection.keepTitle, enabled, change = { model.changeTransfer(selection.copy(keepTitle = it)) }) {
                            Text("沿用标题「$title」")
                        }
                    }
                    content.times.eventTime?.let { time ->
                        SelectionCheckbox(selection.keepEventTime, enabled, change = { model.changeTransfer(selection.copy(keepEventTime = it)) }) {
                            Text("沿用事件时间 · ${time.label}")
                        }
                    }
                    if (selection.itemIds.any { sourceItems.getValue(it).value is ContentItem.Checklist }) {
                        SelectionCheckbox(selection.keepChecked, enabled, change = { model.changeTransfer(selection.copy(keepChecked = it)) }) {
                            Text("沿用清单勾选状态")
                        }
                    }
                    content.place?.let { place ->
                        SelectionCheckbox(selection.placeId == place.id, enabled, change = { on ->
                            model.changeTransfer(selection.copy(placeId = place.id.takeIf { on }))
                        }) {
                            Text("地点", style = MaterialTheme.typography.labelSmall)
                            PlaceDescription(place)
                        }
                    }
                }
                items(content.tags, key = { "tag-" + it.id.value }) { tag ->
                    SelectionCheckbox(tag.id in selection.tagIds, enabled, change = { on ->
                        model.changeTransfer(selection.copy(tagIds = if (on) selection.tagIds + tag.id else selection.tagIds - tag.id))
                    }) { Text("标签 · ${tag.name}") }
                }
                items(content.objects, key = { "object-" + it.id.value }) { obj ->
                    SelectionCheckbox(obj.id in selection.objectIds, enabled, change = { on ->
                        model.changeTransfer(selection.copy(objectIds = if (on) selection.objectIds + obj.id else selection.objectIds - obj.id))
                    }) {
                        Text(obj.kind.label, style = MaterialTheme.typography.labelSmall)
                        ObjectDescription(obj)
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = model::createTransferDraft, enabled = enabled && selection.itemIds.isNotEmpty() && !wouldEmptySource) {
            Text(when { state.error != null -> "重试创建"; split -> "创建拆分草稿"; else -> "创建副本草稿" })
        } },
        dismissButton = { TextButton(onClick = { model.changeTransfer(null) }, enabled = enabled) { Text("取消") } })
}

@Composable
internal fun SelectionCheckbox(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, change: (Boolean) -> Unit,
    label: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(start = 10.dp)) { label() }
    }
}

internal fun ContentItem.selectionLabel(): String = when (this) {
    is ContentItem.EmptyMediaField -> field.name
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
