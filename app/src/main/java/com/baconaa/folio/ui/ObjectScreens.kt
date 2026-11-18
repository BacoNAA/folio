package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baconaa.folio.records.*

internal val ObjectKind.label: String get() = if (this == ObjectKind.PERSON) "人物" else "物件"

@Composable
internal fun ObjectDescription(value: RecordObject) {
    Text(value.info.name, style = MaterialTheme.typography.titleMedium)
    if (value.info.note.isNotEmpty()) Text(value.info.note, fontSize = 12.sp)
    if (value.info.aliases.isNotEmpty()) Text("别名：${value.info.aliases.joinToString("、")}", fontSize = 12.sp)
}

@Composable
internal fun ObjectPicker(model: FolioViewModel, kind: ObjectKind, editing: Boolean, close: () -> Unit) {
    val state = model.state
    val current = state.editor?.objects.orEmpty()
    val selected = if (editing) current.map { it.id }.toSet() else if (kind == ObjectKind.PERSON) state.filterPeople else state.filterThings
    val choices = (if (editing) current else emptyList()) + state.sharedObjects
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var formId by rememberSaveable { mutableStateOf<String?>(null) }
    val all = choices.distinctBy { it.id }.filter { it.kind == kind }
    val filtered = all.filter { value ->
        (listOf(value.info.name, value.info.note) + value.info.aliases).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val enabled = !state.busy && !state.objectsLoading && state.objectsError == null && state.editor?.needsReload != true
    AlertDialog(onDismissRequest = close, title = { Text(if (editing) "选择${kind.label}" else "筛选${kind.label} · 满足任一个") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("查找${kind.label}") },
                supportingText = { Text("名称、别名或区别说明；同名按身份区分。") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("object-search"))
            if (state.objectsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.objectsError?.let { ErrorMessage(it, model::loadObjects) }
            if (state.editor?.needsReload == true) ErrorMessage("对象已保存，请重试读取。", model::reload)
            else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (enabled && filtered.isEmpty()) Text("没有匹配的${kind.label}")
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 280.dp).testTag("object-choices")) {
                if (state.objectsError == null) items(filtered, key = { it.id.value }) { value ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(value.id in selected, onCheckedChange = { checked ->
                            if (editing) model.selectObject(value, checked)
                            else model.filterObjects(kind, if (checked) selected + value.id else selected - value.id)
                        }, enabled = enabled, modifier = Modifier.testTag("object-choice-${value.id.value}"))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            ObjectDescription(value)
                            if (value.isDraft) Text("本草稿新对象", fontSize = 12.sp)
                            TextButton(onClick = { formId = value.id.value }, enabled = enabled,
                                modifier = Modifier.testTag("object-edit-${value.id.value}")) { Text("编辑资料") }
                        }
                    }
                }
            }
            if (editing) TextButton(onClick = { formId = "new" }, enabled = enabled) { Text("新建${kind.label}") }
            Text(if (editing) "选择随记录完成生效；共享资料独立保存。" else "同类满足任一个；人物与物件条件同时满足。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭${kind.label}") } })
    if (formId != null) {
        val initial = all.firstOrNull { it.id.value == formId }
        if (formId == "new" || initial != null) ObjectInfoDialog(model, kind, initial, { formId = null }) { info ->
            if (initial == null) model.createObject(kind, info) else model.updateObject(initial, info)
        }
    }
}

@Composable
private fun ObjectInfoDialog(model: FolioViewModel, kind: ObjectKind, initial: RecordObject?, close: () -> Unit, save: (ObjectInfo) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.info?.name.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.info?.note.orEmpty()) }
    var aliases by rememberSaveable { mutableStateOf(initial?.info?.aliases.orEmpty().joinToString("\n")) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var beforeSave by rememberSaveable { mutableIntStateOf(model.state.objectSaveVersion) }
    val state = model.state
    val enabled = !state.busy && state.editor?.needsReload != true
    LaunchedEffect(state.objectSaveVersion, state.busy, state.editor?.needsReload, state.error) {
        if (submitted && state.objectSaveVersion > beforeSave && enabled && state.error == null) close()
    }
    AlertDialog(onDismissRequest = { if (!state.busy) close() }, title = { Text("${kind.label}资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (initial != null && !initial.isDraft) "这是共享资料。保存后所有引用同步更新，不会提交记录的未完成修改。"
                else "只填名字也可以。同名不会自动合并，完成记录后可在其他记录复用。")
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, enabled = enabled, modifier = Modifier.testTag("object-name"))
            OutlinedTextField(note, { note = it }, label = { Text("区别说明（选填）") }, enabled = enabled, modifier = Modifier.testTag("object-note"))
            OutlinedTextField(aliases, { aliases = it }, label = { Text("别名（每行一个，选填）") }, enabled = enabled, modifier = Modifier.testTag("object-aliases"))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.editor?.needsReload == true) ErrorMessage("对象已保存，请重试读取。", model::reload)
        }
    }, confirmButton = { TextButton(onClick = {
        beforeSave = state.objectSaveVersion; submitted = true
        save(ObjectInfo(name, note, aliases.lines()))
    }, enabled = enabled && name.isNotBlank()) { Text("保存对象") } },
        dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text("取消") } })
}
