package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RelationButton(source: RecordId, model: FolioViewModel) = key(source.value) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = !model.state.busy) { Text("关联记录") }
    if (open) RelationDialog(source, model) { open = false }
}

@Composable
private fun RelationDialog(source: RecordId, model: FolioViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<RelationChoices?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var previous by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(source, refresh) {
        choices = null
        try { choices = model.relationChoices(source) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "读取关联失败，请重试。" }
    }
    fun change(old: RecordId?, next: RecordId?) {
        if (writing) return
        writing = true
        scope.launch {
            try {
                model.changeRelation(source, old, next)
                choosing = false
                previous = null
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (_: Exception) { error = "保存关联失败，请重试。" }
            finally { writing = false; refresh++ }
        }
    }
    AlertDialog(onDismissRequest = { if (!writing) close() }, title = { Text(if (choosing) "选择关联目标" else "关联记录") },
        text = { Column {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                if (choices == null) TextButton(onClick = { error = null; refresh++ }) { Text("重试") }
            }
            val data = choices
            if (data == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data != null) {
                if (!choosing) TextButton(onClick = { choosing = true; previous = null; error = null }, enabled = !writing) { Text("添加关联") }
                val displayed = if (choosing) data.records else data.records.filter { it.id in data.related }
                if (displayed.isEmpty()) Text(if (choosing) "没有其他正式记录。" else "还没有关联记录。")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("relation-list")) {
                    items(displayed, key = { it.id.value }) { card ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            RecordCardSummary(card)
                            if (choosing) {
                                val active = card.id in data.related
                                TextButton(onClick = { change(previous?.let(::RecordId), card.id) }, enabled = !writing && !active,
                                    modifier = Modifier.testTag("relate-${card.id.value}")) { Text(if (active) "已关联" else "选择这条记录") }
                            } else FlowRow {
                                TextButton(onClick = { close(); model.openRecord(card.id) }, enabled = !writing) { Text("打开原记录") }
                                TextButton(onClick = { previous = card.id.value; choosing = true; error = null }, enabled = !writing) { Text("更换目标") }
                                TextButton(onClick = { change(card.id, null) }, enabled = !writing) { Text("解除关联") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = {
            if (choosing) { choosing = false; previous = null; error = null } else close()
        }, enabled = !writing) { Text(if (choosing) "返回关联" else "关闭") } })
}
