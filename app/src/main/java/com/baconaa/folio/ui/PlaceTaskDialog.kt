package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaceTaskDialog(place: Place, close: () -> Unit, write: (String) -> Unit, open: (RecordId) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalTextStore.get(context).placeTasks }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<PlaceTask>?>(null) }
    var choices by remember { mutableStateOf(emptyList<ExperienceChoice>()) }
    var selecting by remember { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "地点事项读写失败，请重试。" }
            finally { busy = false }
        }
    }
    suspend fun reload() { rows = store.read(place.id) }
    LaunchedEffect(place.id) { run { reload() } }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (selecting == null) "下次想试" else "关联已有体验") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) item { LinearProgressIndicator() }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { run { reload() } }) { Text("重新读取") } } }
                if (selecting != null) {
                    if (choices.isEmpty()) item { Text("这个地点还没有已完成的体验。") }
                    items(choices, key = { it.id }) { row ->
                        TextButton(enabled = !busy, onClick = {
                            val id = selecting ?: return@TextButton
                            run { store.link(id, RecordId(row.id)); selecting = null; reload() }
                        }) { Text(row.label) }
                    }
                } else {
                    item {
                        OutlinedTextField(label, { label = it }, label = { Text("下次想做的事") })
                        TextButton(enabled = !busy && label.isNotBlank(), onClick = {
                            run { store.add(place.id, label); label = ""; reload() }
                        }) { Text("添加事项") }
                    }
                    items(rows.orEmpty(), key = { it.row.id }) { task ->
                        Column {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(task.row.checked, enabled = !busy, onCheckedChange = { checked ->
                                    run { store.check(task.row.id, checked); reload() }
                                })
                                Text(task.row.label)
                            }
                            for (experience in task.experiences) TextButton(onClick = { open(RecordId(experience.id)) }) { Text(experience.label) }
                            FlowRow {
                                TextButton(enabled = !busy, onClick = { run {
                                    choices = store.candidates(place.id)
                                    selecting = task.row.id
                                } }) { Text("关联已有体验") }
                                TextButton(enabled = !busy, onClick = { write(task.row.id) }) { Text("补一条体验") }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { if (selecting != null) selecting = null else close() }) {
            Text(if (selecting != null) "返回事项" else "关闭")
        } })
}
