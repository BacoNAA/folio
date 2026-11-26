package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*

sealed interface RecordMerge {
    data class Sources(val ids: List<RecordId>) : RecordMerge
    data class Preview(val sources: Map<RecordId, RecordContent>, val selection: RecordMergeSelection) : RecordMerge {
        val hasMultipleSources: Boolean get() = selection.items.distinctBy { it.source }.size >= 2
    }
}

@Composable
internal fun RecordMergeDialog(merge: RecordMerge, model: FolioViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.loading
    AlertDialog(onDismissRequest = model::cancelMerge,
        title = { Text(if (merge is RecordMerge.Sources) "选择合并来源" else "合并预览") },
        text = {
            Column {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (merge) {
                    is RecordMerge.Sources -> LazyColumn(Modifier.fillMaxWidth().testTag("merge-sources")) {
                        item { Text("选择至少两条正式记录，按选择顺序预览。") }
                        items(state.records, key = { it.id.value }) { record ->
                            val index = merge.ids.indexOf(record.id)
                            SelectionCheckbox(index >= 0, enabled, Modifier.testTag("merge-source-${record.id.value}"), { on ->
                                model.selectMergeSources(if (on) merge.ids + record.id else merge.ids - record.id)
                            }) {
                                if (index >= 0) Text("来源 ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                RecordCardSummary(record)
                            }
                            HorizontalDivider()
                        }
                    }
                    is RecordMerge.Preview -> MergeOptions(merge, enabled, model)
                }
            }
        },
        confirmButton = {
            when (merge) {
                is RecordMerge.Sources -> TextButton(onClick = model::previewMerge, enabled = enabled && merge.ids.size >= 2) {
                    Text("预览合并")
                }
                is RecordMerge.Preview -> TextButton(onClick = model::createMergeDraft, enabled = enabled && merge.hasMultipleSources) {
                    Text("创建合并草稿")
                }
            }
        },
        dismissButton = { TextButton(onClick = model::cancelMerge, enabled = !state.busy) { Text("取消") } })
}

@Composable
private fun MergeOptions(preview: RecordMerge.Preview, enabled: Boolean, model: FolioViewModel) {
    val sources = preview.sources
    val selection = preview.selection
    val itemsByKey = remember(sources) {
        sources.flatMap { (source, content) ->
            content.items.filter { it.hasContent() }.map { MergeItem(source, it.id) to it }
        }.toMap()
    }
    val labels = remember(sources) {
        sources.entries.mapIndexed { index, (id, content) ->
            val title = content.title ?: content.items.firstOrNull { it.hasContent() }?.selectionLabel().orEmpty().take(40)
            id to "来源 ${index + 1} · $title"
        }.toMap()
    }
    val excluded = remember(itemsByKey, selection.items) { itemsByKey.keys - selection.items.toSet() }
    fun move(index: Int, to: Int) {
        val order = selection.items.toMutableList()
        order.add(to, order.removeAt(index))
        model.changeMerge(selection.copy(items = order))
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("merge-options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(if (!preview.hasMultipleSources) "请保留至少两条来源记录的内容。"
                else "核对内容顺序，创建后可继续编辑。原记录将保留。")
            TextButton(onClick = { model.selectMergeSources(sources.keys.toList()) }, enabled = enabled) { Text("重新选择来源") }
        }
        itemsIndexed(selection.items, key = { _, ref -> "chosen-${ref.source.value}-${ref.itemId.value}" }) { index, ref ->
            val item = itemsByKey.getValue(ref)
            Column(Modifier.fillMaxWidth()) {
                Text(labels.getValue(ref.source), style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item is ContentItem.Photo) Photo(item.photoId, thumbnail = true, thumbnailDescription = "合并照片", strokes = item.strokes)
                Text("${index + 1}. ${item.selectionLabel()}", Modifier.testTag("merge-preview-${ref.itemId.value}"),
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                FlowRow {
                    TextButton(onClick = { move(index, index - 1) }, enabled = enabled && index > 0,
                        modifier = Modifier.testTag("merge-up-${ref.itemId.value}")) { Text("上移") }
                    TextButton(onClick = { move(index, index + 1) }, enabled = enabled && index < selection.items.lastIndex,
                        modifier = Modifier.testTag("merge-down-${ref.itemId.value}")) { Text("下移") }
                    TextButton(onClick = { model.changeMerge(selection.copy(items = selection.items - ref)) }, enabled = enabled,
                        modifier = Modifier.testTag("merge-remove-${ref.itemId.value}")) { Text("移除") }
                }
                HorizontalDivider()
            }
        }
        if (excluded.isNotEmpty()) item { Text("已移除内容", style = MaterialTheme.typography.titleSmall) }
        items(excluded.toList(), key = { "excluded-${it.source.value}-${it.itemId.value}" }) { ref ->
            TextButton(onClick = { model.changeMerge(selection.copy(items = selection.items + ref)) }, enabled = enabled) {
                Text("加回 · ${labels.getValue(ref.source)} · ${itemsByKey.getValue(ref).selectionLabel()}", maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        item { Text("事件时间", style = MaterialTheme.typography.titleSmall) }
        item {
            FilterChip(selection.eventTimeSource == null, { model.changeMerge(selection.copy(eventTimeSource = null)) },
                enabled = enabled, label = { Text("留空") })
        }
        sources.forEach { (source, content) ->
            content.times.eventTime?.let { time ->
                item(key = "time-${source.value}") {
                    FilterChip(selection.eventTimeSource == source, { model.changeMerge(selection.copy(eventTimeSource = source)) },
                        enabled = enabled, label = { Text("${labels.getValue(source)} · ${time.label}") })
                }
            }
        }
        if (selection.items.any { itemsByKey[it] is ContentItem.Checklist }) item {
            SelectionCheckbox(selection.keepChecked, enabled, change = { model.changeMerge(selection.copy(keepChecked = it)) }) {
                Text("沿用清单勾选状态")
            }
        }
        val places = sources.values.mapNotNull { it.place }.distinctBy { it.id }
        val tags = sources.values.flatMap { it.tags }.distinctBy { it.id }
        val objects = sources.values.flatMap { it.objects }.distinctBy { it.id }
        if (places.isNotEmpty() || tags.isNotEmpty() || objects.isNotEmpty()) item {
            Text("沿用线索", style = MaterialTheme.typography.titleSmall)
        }
        items(places, key = { "place-${it.id.value}" }) { place ->
            SelectionCheckbox(selection.placeId == place.id, enabled, change = { on ->
                model.changeMerge(selection.copy(placeId = place.id.takeIf { on }))
            }) {
                Text("地点", style = MaterialTheme.typography.labelSmall)
                PlaceDescription(place)
            }
        }
        items(tags, key = { "tag-${it.id.value}" }) { tag ->
            SelectionCheckbox(tag.id in selection.tagIds, enabled, change = { on ->
                model.changeMerge(selection.copy(tagIds = if (on) selection.tagIds + tag.id else selection.tagIds - tag.id))
            }) { Text("标签 · ${tag.name}") }
        }
        items(objects, key = { "object-${it.id.value}" }) { obj ->
            SelectionCheckbox(obj.id in selection.objectIds, enabled, change = { on ->
                model.changeMerge(selection.copy(objectIds = if (on) selection.objectIds + obj.id else selection.objectIds - obj.id))
            }) {
                Text(obj.kind.label, style = MaterialTheme.typography.labelSmall)
                ObjectDescription(obj)
            }
        }
    }
}
