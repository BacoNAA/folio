package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.baconaa.folio.records.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private suspend fun reportStateError(onError: (String) -> Unit, work: suspend () -> Unit) {
    try { work() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { onError((e as? IllegalArgumentException)?.message ?: "读写失败，请重试。") }
}

@Composable
internal fun TargetStateButton(model: FolioViewModel, target: StateTarget) {
    val states = model.customStates ?: return
    var open by rememberSaveable(target.key) { mutableStateOf(false) }
    var revision by remember(target) { mutableIntStateOf(0) }
    var label by remember(target) { mutableStateOf("当前状态") }
    LaunchedEffect(target, revision) {
        reportStateError({ label = "状态读取失败 · 重试" }) {
            label = states.currentState(target)?.let { "当前状态 · ${it.label}" } ?: "当前状态"
        }
    }
    TextButton(onClick = { open = true }) { Text(label) }
    if (open) StateEditor(states, target, close = { revision++; open = false })
}

@Composable
private fun StateEditor(states: RecordStates, target: StateTarget, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<TargetStates?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var keep by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    val request = rememberSaveable(selected, keep, date, note) { UUID.randomUUID().toString() }
    LaunchedEffect(revision) {
        error = null
        reportStateError({ error = it }) { content = states.readStates(target) }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("${target.kind.label}状态") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val data = content
                if (data != null) {
                    Text("当前状态 · ${data.current?.label ?: "未设置"}")
                    StatePicker(data.choices, selected ?: data.current?.id, !busy) { selected = it; error = null }
                    TextButton(onClick = { creating = true }, enabled = !busy) { Text("新建状态") }
                    Row(Modifier.toggleable(keep, enabled = !busy, role = Role.Checkbox) { keep = it }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keep, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("留下这次变化")
                    }
                    if (keep) {
                        OutlinedTextField(date, { date = it }, enabled = !busy, singleLine = true,
                            label = { Text("变化日期（年-月-日）") })
                        OutlinedTextField(note, { note = it }, enabled = !busy, label = { Text("变化说明") })
                    }
                    HorizontalDivider()
                    Text("保留的变化", style = MaterialTheme.typography.titleSmall)
                    if (data.history.isEmpty()) Text("还没有保留的变化")
                    data.history.forEach {
                        Text("${it.entry.date} · ${it.previous?.label ?: "未设置"} → ${it.next.label}")
                        if (it.entry.note.isNotBlank()) Text(it.entry.note)
                    }
                } else if (error == null) LinearProgressIndicator()
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (data == null) TextButton(onClick = { revision++ }) { Text("重试读取") }
                }
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("关闭") } },
        confirmButton = {
            TextButton(enabled = !busy && content != null && selected != null && selected != content?.current?.id, onClick = {
                val next = selected ?: return@TextButton
                val change = if (keep) {
                    val parsed = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                    if (parsed == null) { error = "请按年-月-日填写有效日期。"; return@TextButton }
                    StateNote(parsed, note)
                } else null
                busy = true
                error = null
                scope.launch {
                    try {
                        reportStateError({ error = it }) {
                            states.setState(target, content?.current?.id, next, request, change)
                            close()
                        }
                    }
                    finally { busy = false }
                }
            }) { Text("保存状态") }
        })
    if (creating) DefineStateDialog(states, { creating = false }) {
        selected = it
        creating = false
        revision++
    }
}

@Composable
private fun StatePicker(choices: List<CustomState>, selected: String?, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty()) {
            Text(choices.firstOrNull { it.id == selected }?.label ?: "选择状态")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { state ->
                DropdownMenuItem(text = { Text(state.label) }, onClick = { expanded = false; choose(state.id) })
            }
        }
    }
}

@Composable
private fun DefineStateDialog(states: RecordStates, close: () -> Unit, created: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("新建状态") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("状态名称") }, singleLine = true, enabled = !busy)
            OutlinedTextField(pattern, { pattern = it }, label = { Text("图案（符号或表情）") }, singleLine = true, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && pattern.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try { reportStateError({ error = it }) { states.defineState(CustomState(id, name, pattern)); created(id) } }
                finally { busy = false }
            }
        }) { Text("创建") } })
}

@Composable
internal fun StateFilterButton(model: FolioViewModel) {
    val states = model.customStates ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("按状态查看") }
    if (!open) return
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyList<CustomState>()) }
    var matches by remember { mutableStateOf(emptyList<StateMatch>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected, revision) {
        loading = true
        error = null
        reportStateError({ error = it }) {
            choices = states.listStates()
            matches = selected?.let { states.filterState(it) }.orEmpty()
        }
        loading = false
    }
    Dialog(onDismissRequest = { open = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按当前状态查看", style = MaterialTheme.typography.titleLarge)
                StatePicker(choices, selected) { selected = it }
                when {
                    loading -> LinearProgressIndicator()
                    error != null -> ErrorMessage(requireNotNull(error)) { revision++ }
                    choices.isEmpty() -> Text("可在人物、物件、地点小册或记录详情中新建状态。")
                    selected != null && matches.isEmpty() -> Text("没有使用此状态的人物、物件、地点或记录")
                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(matches, key = { "${it.kind}:${it.id}" }) { match ->
                            TextButton(onClick = {
                                scope.launch { reportStateError({ error = it }) { model.openStateMatch(match); open = false } }
                            }) { Text(listOf(match.kind.label, match.name, match.detail).filter(String::isNotBlank).joinToString(" · ")) }
                        }
                    }
                }
                TextButton(onClick = { open = false }) { Text("关闭") }
            }
        }
    }
}
