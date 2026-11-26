package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baconaa.folio.media.AudioId
import com.baconaa.folio.records.*

@Composable
internal fun MixedEditorScreen(model: FolioViewModel, launchMedia: (String, ItemId?) -> Unit,
    chooseDocument: (ImportType, ItemId?) -> Unit, discard: () -> Unit) {
    val state = model.state
    val editor = state.editor ?: return
    val scroll = rememberLazyListState()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addAfter by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !state.busy && !editor.needsReload
    val structureEnabled = enabled && !state.recording && state.pendingAttachment == null && !state.importFailed
    LaunchedEffect(editor.activeItemId, editor.invalidLink) {
        val index = editor.items.indexOfFirst { it.id == editor.activeItemId }
        if (index >= 0) {
            if (editor.invalidLink?.itemId == editor.activeItemId) scroll.scrollToItem(index + 1)
            else scroll.animateScrollToItem(if (index == 0) 0 else index + 1)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LazyColumn(Modifier.weight(1f).testTag("mixed-editor"), state = scroll,
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("optional-title") {
                OutlinedTextField(editor.title, model::changeTitle, label = { Text("标题（选填）") },
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("record-title"))
            }
            itemsIndexed(editor.items, key = { _, item -> item.id.value }) { index, item ->
                var menu by remember { mutableStateOf(false) }
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.fieldName ?: when (item) {
                            is ContentItem.EmptyMediaField -> item.field.type.label
                            is ContentItem.Text -> "文字"
                            is ContentItem.Photo -> "照片"
                            is ContentItem.Pdf -> "附件"
                            is ContentItem.Link -> "网址"
                            is ContentItem.Video -> "视频"
                            is ContentItem.Audio -> "录音"
                            is ContentItem.Checklist -> "清单"
                        }, Modifier.weight(1f), fontSize = 12.sp)
                        Box {
                            TextButton(onClick = { menu = true }, enabled = structureEnabled,
                                modifier = Modifier.testTag("item-menu-$index").semantics { contentDescription = "第 ${index + 1} 项的更多操作" }) { Text("⋯") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("在此后添加") }, onClick = { menu = false; addAfter = item.id.value; showAdd = true })
                                DropdownMenuItem(text = { Text("上移一项") }, enabled = index > 0,
                                    onClick = { menu = false; model.move(item.id, MoveDirection.UP) })
                                DropdownMenuItem(text = { Text("下移一项") }, enabled = index < editor.items.lastIndex,
                                    onClick = { menu = false; model.move(item.id, MoveDirection.DOWN) })
                                DropdownMenuItem(text = { Text("移除这一项") }, onClick = { menu = false; model.remove(item.id) })
                            }
                        }
                    }
                    when (item) {
                        is ContentItem.EmptyMediaField -> FlowRow {
                            if (item.field.type == TemplateFieldType.PHOTO) {
                                OutlinedButton(onClick = { launchMedia("field-photo", item.id) }, enabled = structureEnabled) { Text("选择照片") }
                            } else {
                                OutlinedButton(onClick = { launchMedia("field-audio", item.id) }, enabled = structureEnabled) { Text("录制声音") }
                                TextButton(onClick = { launchMedia("field-audio-file", item.id) }, enabled = structureEnabled) { Text("选择已有录音") }
                            }
                        }
                        is ContentItem.Text -> {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(editor.activeItemId, enabled) {
                                if (enabled && editor.activeItemId == item.id) focus.requestFocus()
                            }
                            OutlinedTextField(value = editor.textValues[item.id] ?: TextFieldValue(item.text),
                                onValueChange = { model.changeText(item.id, it) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                    .testTag(if (item == editor.items.filterIsInstance<ContentItem.Text>().firstOrNull()) "editor" else "text-${item.id.value}"),
                                minLines = 4, maxLines = 12, placeholder = { Text("写下一句话，也可以慢慢写……") },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp))
                        }
                        is ContentItem.Pdf -> PdfAttachment(item, model)
                        is ContentItem.Link -> LinkEditor(item, enabled, structureEnabled,
                            editor.invalidLink?.takeIf { it.itemId == item.id && it.address == item.address },
                            model::changeLink, onScreenshot = { launchMedia("link-screenshot", item.id) })
                        is ContentItem.Video -> VideoAttachment(item, model)
                        is ContentItem.Photo -> {
                            Photo(item.photoId, strokes = item.strokes)
                            TextButton(onClick = { model.openPhotoMarkup(item) }, enabled = structureEnabled) { Text("圈画照片") }
                            OutlinedTextField(item.caption, { model.changeCaption(item.id, it) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth(), label = { Text("照片说明（选填）") })
                        }
                        is ContentItem.Audio -> {
                            AudioControl(item.audioId, item.durationMillis, model)
                            OutlinedTextField(item.caption, { model.changeCaption(item.id, it) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth(), label = { Text("录音说明（选填）") })
                        }
                        is ContentItem.Checklist -> {
                            item.entries.forEachIndexed { entryIndex, entry -> key(entry.id.value) {
                                val focus = remember { FocusRequester() }
                                LaunchedEffect(Unit) {
                                    if (enabled && editor.activeItemId == item.id &&
                                        (item.entries.size == 1 || (entryIndex == item.entries.lastIndex && entry.text.isEmpty()))) focus.requestFocus()
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.checked, { checked -> model.changeChecklist(item.id,
                                        item.entries.map { if (it.id == entry.id) it.copy(checked = checked) else it }) }, enabled = enabled,
                                        modifier = Modifier.semantics { contentDescription = "完成事项 ${entryIndex + 1}" })
                                    OutlinedTextField(entry.text, { text -> model.changeChecklist(item.id,
                                        item.entries.map { if (it.id == entry.id) it.copy(text = text) else it }) }, enabled = enabled,
                                        modifier = Modifier.weight(1f).focusRequester(focus).testTag("check-entry-${entry.id.value}"), label = { Text("事项 ${entryIndex + 1}") })
                                    TextButton(onClick = { model.changeChecklist(item.id, item.entries.filterNot { it.id == entry.id }) },
                                        enabled = enabled, modifier = Modifier.semantics { contentDescription = "删除事项 ${entryIndex + 1}" }) { Text("×") }
                                }
                            } }
                            TextButton(onClick = { model.changeChecklist(item.id, item.entries + ChecklistEntry(ChecklistEntryId.create(), "")) },
                                enabled = enabled) { Text("＋ 添加事项") }
                        }
                    }
                }
            }
            item("add") {
                OutlinedButton(onClick = { addAfter = null; showAdd = true }, enabled = structureEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("add-content")) { Text("＋ 添加内容") }
            }
            item("clues") { EditorClues(model) }
        }
        if (state.recording) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正在录音", Modifier.weight(1f))
            TextButton(onClick = model::cancelRecording, enabled = !state.busy) { Text("取消录音") }
            Button(onClick = { model.stopRecording() }, enabled = !state.busy) { Text("停止并保存") }
        }
        if (state.pendingAttachment != null) ErrorMessage("原件已保存，尚未加入草稿。", model::retryAttachment)
        if (state.importFailed) {
            ErrorMessage("照片尚未导入。", model::retryPhoto)
            TextButton(onClick = model::cancelPhotoImport, enabled = !state.busy) { Text("放弃这张照片，继续编辑") }
        }
        state.saveError?.let { ErrorMessage(it, model::save) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.recording -> "录音尚未保存"
                state.saveError != null || editor.needsReload -> "尚未确认保存"
                state.pendingAttachment != null || state.importFailed -> "媒体待保存"
                editor.draftId == null -> "有内容就能保存"
                editor.revision != editor.savedRevision -> "正在保存…"
                else -> "草稿已保存"
            }, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editor.canUndo) TextButton(onClick = model::undo, enabled = structureEnabled) { Text("撤销移除") }
            TextButton(onClick = discard, enabled = !state.busy) { Text("放弃修改") }
        }
    }
    editor.markingPhoto?.let { PhotoMarkupDialog(it, model) }
    editor.photoTimeOffer?.let { offer ->
        AlertDialog(onDismissRequest = { model.resolvePhotoTime(false) }, title = { Text("采用照片拍摄时间？") },
            text = { Column {
                Text(offer.time.label)
                if (offer.usesDeviceZone) Text("照片未注明时区，将按设备时区 ${offer.time.zone.id} 保存。")
                Text("当前事件时间：${editor.eventTime.label()}")
            } }, confirmButton = { TextButton(onClick = { model.resolvePhotoTime(true) }, enabled = enabled) { Text("采用拍摄时间") } },
            dismissButton = { TextButton(onClick = { model.resolvePhotoTime(false) }, enabled = enabled) { Text("保留当前时间") } })
    }
    editor.fileImportFailure?.let { failure ->
        AlertDialog(onDismissRequest = model::dismissFileImportFailure, title = { Text("导入失败") },
            text = { Text(failure.message, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                model.dismissFileImportFailure()
                if (failure.audioField != null) launchMedia("field-audio-file", failure.audioField)
                else chooseDocument(failure.type, failure.after)
            }) { Text("重新选择") } },
            dismissButton = { TextButton(onClick = model::dismissFileImportFailure) { Text("继续编辑") } })
    }
    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text(if (addAfter == null) "添加内容" else "在此后添加") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("text" to "文字", "camera" to "拍照", "photo" to "选图", "audio" to "录音", "checklist" to "清单", "link" to "网址").forEach { (kind, label) ->
                TextButton(onClick = {
                    showAdd = false
                    val after = addAfter?.let(::ItemId)
                    when (kind) {
                        "text" -> model.addText(after)
                        "checklist" -> model.addChecklist(after)
                        "link" -> model.addLink(after)
                        else -> launchMedia(kind, after)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            listOf(ImportType.PDF to "PDF 附件", ImportType.AUDIO to "导入已有录音", ImportType.VIDEO to "导入视频").forEach { (type, label) ->
                TextButton(onClick = {
                    showAdd = false
                    chooseDocument(type, addAfter?.let(::ItemId))
                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        } }, confirmButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } })
}

internal fun formatAudioDuration(millis: Long): String =
    "${millis / 60_000}:${((millis / 1_000) % 60).toString().padStart(2, '0')}"

@Composable
internal fun AudioControl(id: AudioId, duration: Long, model: FolioViewModel) {
    val playback by model.playback.collectAsState()
    val current = playback.audioId == id
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("录音 · ${formatAudioDuration(duration)}", Modifier.weight(1f))
        TextButton(onClick = { model.play(id) }, enabled = !model.state.recording) {
            Text(if (current && playback.isPlaying) "暂停" else if (current && playback.isBuffering) "正在加载" else "试听")
        }
    }
    if (current) playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
}
