package com.baconaa.folio.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baconaa.folio.records.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal fun EventTime?.label(): String = when (this) {
    null -> "暂不填写"
    is EventTime.Season, is EventTime.Description -> dateRange?.let { "$label（${it.start} 至 ${it.endInclusive}）" } ?: label
    else -> label
}

@Composable
internal fun EditorClues(model: FolioViewModel) {
    val editor = model.state.editor ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var timeDialog by rememberSaveable { mutableStateOf(false) }
    var tagDialog by rememberSaveable { mutableStateOf(false) }
    var placeDialog by rememberSaveable { mutableStateOf(false) }
    var objectKind by rememberSaveable { mutableStateOf<ObjectKind?>(null) }
    val enabled = !model.state.busy && !editor.needsReload && !model.state.recording && model.state.pendingAttachment == null
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("editor-clues")) {
            Text(if (expanded) "收起时间、地点与线索" else "时间、地点与线索 · 选填")
        }
        if (expanded) {
            TextButton(onClick = { timeDialog = true }, enabled = enabled, modifier = Modifier.testTag("edit-time")) {
                Text("事件时间：${editor.eventTime.label()}")
            }
            TextButton(onClick = { tagDialog = true; model.loadTags() }, enabled = enabled && editor.hasContent,
                modifier = Modifier.testTag("edit-tags")) {
                Text(if (editor.tags.isEmpty()) "选择或新建文字标签" else "标签：${editor.tags.joinToString("、") { it.name }}")
            }
            if (!editor.hasContent) Text("先留下一点内容，再添加标签。", fontSize = 12.sp)
            TextButton(onClick = { model.loadPlaces(); placeDialog = true }, enabled = enabled && editor.hasContent,
                modifier = Modifier.testTag("edit-place")) { Text("地点：${editor.place?.info?.name ?: "暂不填写"}") }
            ObjectKind.entries.forEach { kind ->
                TextButton(onClick = { model.loadObjects(); objectKind = kind }, enabled = enabled && editor.hasContent,
                    modifier = Modifier.testTag("edit-${kind.name}")) {
                    val names = editor.objects.filter { it.kind == kind }.joinToString("、") { it.info.name }
                    Text("${kind.label}：${names.ifEmpty { "暂不填写" }}")
                }
            }
        }
    }
    if (timeDialog) EventTimeDialog(editor.eventTime, onDismiss = { timeDialog = false }) {
        model.changeEventTime(it); timeDialog = false
    }
    if (tagDialog) TagDialog(model, editing = true) { tagDialog = false }
    if (placeDialog) PlacePicker(model) { placeDialog = false }
    objectKind?.let { ObjectPicker(model, it, editing = true) { objectKind = null } }
}

@Composable
private fun EventTimeDialog(value: EventTime?, onDismiss: () -> Unit, save: (EventTime?) -> Unit) {
    val exact = value as? EventTime.Exact
    var date by rememberSaveable { mutableStateOf(exact?.date?.toString().orEmpty()) }
    var time by rememberSaveable { mutableStateOf(exact?.time?.toString().orEmpty()) }
    var zone by rememberSaveable { mutableStateOf(value?.zone?.id ?: ZoneId.systemDefault().id) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("事件时间") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (value != null && exact == null) Text("当前为${value.label()}。填写具体日期可替换原时间。")
            Text("填写事情发生的日期，时刻可留空。补记旧事也可以暂不填写。")
            OutlinedTextField(date, { date = it; error = null }, label = { Text("日期 · YYYY-MM-DD") },
                singleLine = true, modifier = Modifier.testTag("event-date"))
            OutlinedTextField(time, { time = it; error = null }, label = { Text("时刻 · HH:mm（选填）") },
                singleLine = true, modifier = Modifier.testTag("event-time"))
            OutlinedTextField(zone, { zone = it; error = null }, label = { Text("事件所在地时区") },
                supportingText = { Text("如 Asia/Shanghai、Asia/Tokyo；回看保持当地时间。") }, singleLine = true,
                modifier = Modifier.testTag("event-zone"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { save(null) }) { Text("暂不填写") }
        }
    }, confirmButton = { TextButton(onClick = {
        val result = runCatching { EventTime(LocalDate.parse(date.trim()),
            time.trim().takeIf { it.isNotEmpty() }?.let(LocalTime::parse), ZoneId.of(zone.trim())) }
        result.onSuccess(save).onFailure { error = "请检查日期、时刻和时区是否有效。" }
    }) { Text("应用时间") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun TagDialog(model: FolioViewModel, editing: Boolean, close: () -> Unit) {
    val state = model.state
    val selected = if (editing) state.editor?.tags.orEmpty().map { it.id }.toSet() else state.filterTags
    val choices = (state.sharedTags + if (editing) state.editor?.tags.orEmpty() else emptyList()).distinctBy { it.id }
    var name by rememberSaveable { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamed by rememberSaveable { mutableStateOf("") }
    var renameSubmitted by rememberSaveable { mutableStateOf(false) }
    var pendingTagName by rememberSaveable { mutableStateOf<String?>(null) }
    var previousTagCount by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.editor?.tags, state.busy) {
        if (!state.busy && pendingTagName != null && state.editor?.tags.orEmpty().size > previousTagCount &&
            state.editor?.tags.orEmpty().any { it.name == pendingTagName }) {
            name = ""; pendingTagName = null
        }
        if (renameSubmitted && !state.busy && choices.any { it.id.value == renameId && it.name == renamed.trim() }) {
            renameSubmitted = false; renameId = null
        }
    }
    val enabled = !state.busy && !state.tagsLoading && state.tagsError == null && state.editor?.needsReload != true
    AlertDialog(onDismissRequest = close, title = { Text(if (editing) "文字标签" else "筛选标签 · 满足任一个") }, text = {
        Column {
            if (state.tagsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.tagsError?.let { ErrorMessage(it, model::loadTags) }
            if (editing) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.editor?.needsReload == true) ErrorMessage("标签已保存，请重试读取。", model::reload)
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 280.dp)) {
                items(choices, key = { it.id.value }) { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tag.id in selected, { checked ->
                            if (editing) model.selectTag(tag, checked)
                            else model.filterByTags(if (checked) selected + tag.id else selected - tag.id)
                        }, enabled = enabled, modifier = Modifier.testTag("tag-choice-${tag.id.value}"))
                        val same = choices.filter { it.name == tag.name }
                        Text(tag.name + if (same.size > 1) "（同名 ${same.indexOf(tag) + 1}）" else "", Modifier.weight(1f))
                        if (editing && tag.id in selected) TextButton(onClick = { renameId = tag.id.value; renamed = tag.name; renameSubmitted = false }, enabled = enabled) { Text("改名") }
                    }
                }
            }
            if (editing) {
                OutlinedTextField(name, { name = it }, label = { Text("新标签名称") }, singleLine = true,
                    modifier = Modifier.testTag("new-tag"))
                TextButton(onClick = {
                    previousTagCount = state.editor?.tags.orEmpty().size; pendingTagName = name.trim(); model.createTag(name)
                }, enabled = enabled && name.isNotBlank()) { Text("新建并选中") }
                Text("新标签随本次记录完成后供其他记录选用；同名标签不会自动合并。", fontSize = 12.sp)
            } else if (choices.isEmpty() && state.tagsError == null && !state.tagsLoading) Text("还没有已完成的标签。")
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭标签") } })
    val tag = choices.firstOrNull { it.id.value == renameId }
    if (tag != null) {
        val shared = state.sharedTags.any { it.id == tag.id }
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("修改标签名称") }, text = { Column {
            Text(if (shared) "这是共享标签，改名会立即同步所有引用它的记录和草稿。" else "这是当前草稿的新标签。")
            OutlinedTextField(renamed, { renamed = it }, label = { Text("标签名称") }, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { renameSubmitted = true; model.renameTag(tag, renamed, shared) },
            enabled = enabled && renamed.isNotBlank()) { Text("保存名称") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } })
    }
}

private val MediaFilter.label get() = when (this) {
    MediaFilter.PHOTO -> "有照片"
    MediaFilter.AUDIO -> "有录音"
    MediaFilter.CHECKLIST -> "有清单"
    MediaFilter.TEXT_ONLY -> "纯文字"
}

@Composable
internal fun SearchFilters(model: FolioViewModel) {
    var showMedia by rememberSaveable { mutableStateOf(false) }
    val state = model.state
    var showTags by rememberSaveable { mutableStateOf(false) }
    var objectKind by rememberSaveable { mutableStateOf<ObjectKind?>(null) }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(state.starredOnly, { model.filterStarred(!state.starredOnly) }, label = { Text("只看星标") })
            TextButton(onClick = { showTags = true; model.loadTags() }) { Text("筛选标签${if (state.filterTags.isEmpty()) "" else " · ${state.filterTags.size}"}") }
            if (state.starredOnly || state.filterTags.isNotEmpty() || state.filterPlace != null || state.filterPeople.isNotEmpty() || state.filterThings.isNotEmpty() || state.mediaTypes.isNotEmpty())
                TextButton(onClick = model::clearFilters) { Text("清除筛选") }
            ObjectKind.entries.forEach { kind ->
                TextButton(onClick = { model.loadObjects(); objectKind = kind }) { Text("筛选${kind.label}") }
            }
            TextButton(onClick = { showMedia = true }) { Text("内容类型") }
        }
        if (state.filterTags.isNotEmpty() || state.filterPeople.isNotEmpty() || state.filterThings.isNotEmpty() || state.mediaTypes.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            state.mediaTypes.sortedBy { it.ordinal }.forEachIndexed { index, type ->
                if (index > 0) Text("或", Modifier.align(Alignment.CenterVertically))
                InputChip(selected = true, onClick = { model.filterMedia(state.mediaTypes - type) },
                    label = { Text("${type.label} ×") })
            }
            state.filterTags.forEach { id ->
                InputChip(selected = true, onClick = { model.filterByTags(state.filterTags - id) },
                    label = { Text("${state.sharedTags.firstOrNull { it.id == id }?.name ?: "已选标签"} ×") })
            }
            ObjectKind.entries.forEach { kind ->
                val ids = if (kind == ObjectKind.PERSON) state.filterPeople else state.filterThings
                ids.forEach { id ->
                    val value = state.sharedObjects.firstOrNull { it.id == id }
                    InputChip(selected = true, onClick = { model.filterObjects(kind, ids - id) },
                        modifier = Modifier.testTag("remove-object-${id.value}"),
                        label = { Text("${kind.label}：${value?.info?.name ?: "已选对象"}${value?.info?.note?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()} ×") })
                }
            }
        }
    }
    if (showMedia) AlertDialog(onDismissRequest = { showMedia = false }, title = { Text("内容类型 · 满足任一种") },
        text = { Column { MediaFilter.entries.forEach { type ->
            val selected = type in state.mediaTypes
            Row(Modifier.fillMaxWidth().toggleable(value = selected, role = androidx.compose.ui.semantics.Role.Checkbox) {
                model.filterMedia(if (it) state.mediaTypes + type else state.mediaTypes - type)
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, onCheckedChange = null)
                Text(type.label, Modifier.padding(start = 12.dp))
            }
        } } }, confirmButton = { TextButton(onClick = { showMedia = false }) { Text("完成选择") } })
    if (showTags) TagDialog(model, editing = false) { showTags = false }
    objectKind?.let { ObjectPicker(model, it, editing = false) { objectKind = null } }
}

@Composable
internal fun TrashScreen(model: FolioViewModel) {
    val state = model.state
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("记录保留至手动清理，恢复不会自动提交未完成修改。", fontSize = 12.sp)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.trash.isEmpty() && !state.loading && state.error == null) Text("回收站为空", Modifier.padding(vertical = 24.dp))
        LazyColumn(Modifier.testTag("trash-list")) {
            if (state.error == null) items(state.trash, key = { it.id.value }) { record ->
                Column(Modifier.fillMaxWidth().clickable { model.openTrashRecord(record.id) }.padding(vertical = 16.dp)) {
                    Text(record.excerpt.ifBlank { "媒体记录" })
                    record.firstPhotoId?.let { Photo(it) }
                    if (record.hasDraft) Text("有未完成修改", fontSize = 12.sp)
                    Text("删除于 ${formatDate(record.deletedAt)}", fontSize = 12.sp)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun RecordContents(content: RecordContent, model: FolioViewModel) {
    Text("事件时间：${content.times.eventTime.label()}", fontSize = 12.sp)
    content.place?.let { Text("地点", fontSize = 12.sp); PlaceDescription(it) }
    content.objects.forEach { Text(it.kind.label, fontSize = 12.sp); ObjectDescription(it) }
    content.items.forEach { item -> when (item) {
        is ContentItem.Text -> Text(item.text, fontSize = 18.sp, lineHeight = 32.sp)
        is ContentItem.Photo -> { Photo(item.photoId); if (item.caption.isNotEmpty()) Text(item.caption) }
        is ContentItem.Audio -> { AudioControl(item.audioId, item.durationMillis, model); if (item.caption.isNotEmpty()) Text(item.caption) }
        is ContentItem.Checklist -> item.entries.forEach { Text("${if (it.checked) "☑" else "☐"} ${it.text}") }
    } }
    if (content.tags.isNotEmpty()) Text(content.tags.joinToString(" · ") { it.name })
    HorizontalDivider()
    Text("记录于 ${formatDate(content.times.firstRecordedAt)}", fontSize = 12.sp)
    if (content.times.modifiedAt != content.times.firstRecordedAt) Text("修改于 ${formatDate(content.times.modifiedAt)}", fontSize = 12.sp)
}
