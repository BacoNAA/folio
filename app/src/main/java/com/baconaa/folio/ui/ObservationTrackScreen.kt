package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*

@Composable
internal fun ObservationTrackScreen(model: FolioViewModel) {
    val state = model.state
    val owner = state.track ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    var recordId by rememberSaveable { mutableStateOf<String?>(null) }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    ReadLocalData(owner to state.trackVersion, { model.trackNodes(owner) }, "轨道读取失败。") { nodes ->
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(state.trackName, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { recordId = null; itemId = null; note = ""; editing = true }) { Text("添加观察") }
                if (nodes.isEmpty()) Text("还没有观察")
            }
            items(nodes, key = { it.record.value + "/" + it.itemId.value }) { node ->
                Column {
                    Text(node.eventTime.label(), style = MaterialTheme.typography.labelLarge)
                    node.title?.let { Text(it) }
                    val item = node.item
                    if (item is ContentItem.Photo) Photo(item.photoId, strokes = item.strokes)
                    if (item is ContentItem.Audio) AudioControl(item.audioId, item.durationMillis, model)
                    Text(item?.selectionLabel() ?: "来源项已移除")
                    if (node.note.isNotBlank()) Text(node.note)
                    FlowRow {
                        TextButton(onClick = { model.openRecord(node.record) }) { Text("原记录") }
                        if (item != null) TextButton(onClick = {
                            recordId = node.record.value; itemId = node.itemId.value; note = node.note; editing = true
                        }) { Text("修改说明") }
                        TextButton(onClick = { model.removeTrackNode(node) }, enabled = !state.busy) { Text("移出观察") }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (editing) {
            val record = recordId?.let(::RecordId)
            val item = itemId?.let(::ItemId)
            AlertDialog(onDismissRequest = { if (!state.busy) editing = false }, title = { Text(when {
                record == null -> "选择小册中的记录"
                item == null -> "选择代表素材"
                else -> "观察说明"
            }) }, text = {
                Column {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        record == null -> ReadLocalData(owner, { model.trackSources(owner) }, "来源记录读取失败。") { records ->
                            if (records.isEmpty()) Text("小册中还没有正式记录")
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(records, key = { it.id.value }) { source ->
                                    TextButton(onClick = { recordId = source.id.value },
                                        modifier = Modifier.fillMaxWidth().testTag("observe-record-${source.id.value}")) {
                                        Column(Modifier.fillMaxWidth()) { RecordCardSummary(source) }
                                    }
                                }
                            }
                        }
                        item == null -> {
                            TextButton(onClick = { recordId = null }) { Text("重新选择记录") }
                            ReadLocalData(record, { model.readSourceRecord(record) }, "来源内容读取失败。") { source ->
                                val choices = source?.items.orEmpty().filter { it.hasContent() }
                                if (choices.isEmpty()) Text("来源记录已不可选")
                                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                    items(choices, key = { it.id.value }) { choice ->
                                        TextButton(onClick = {
                                            itemId = choice.id.value
                                            note = nodes.find { it.record == record && it.itemId == choice.id }?.note.orEmpty()
                                        }, modifier = Modifier.fillMaxWidth().testTag("observe-item-${choice.id.value}")) {
                                            if (choice is ContentItem.Photo) Photo(choice.photoId, thumbnail = true, strokes = choice.strokes)
                                            Text(choice.selectionLabel(), Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        else -> OutlinedTextField(note, { note = it }, label = { Text("观察说明（选填）") }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("observation-note"))
                    }
                }
            }, confirmButton = {
                if (record != null && item != null) TextButton(onClick = {
                    model.saveTrackNode(record, item, note) { editing = false }
                }, enabled = !state.busy) { Text("保存观察") }
            }, dismissButton = { TextButton(onClick = { editing = false }, enabled = !state.busy) { Text("取消") } })
        }
    }
}
