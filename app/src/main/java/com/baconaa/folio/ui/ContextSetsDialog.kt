package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ContextSetEntry(model: FolioViewModel, editingDraft: Boolean, enabled: Boolean = true) {
    var opened by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { opened = true }, enabled = enabled) { Text("情境组合") }
    if (opened) ContextSetsDialog(model, editingDraft) { opened = false }
}

@Composable
private fun ContextSetsDialog(model: FolioViewModel, editingDraft: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalTextStore.get(context) }
    val scope = rememberCoroutineScope()
    var sets by remember { mutableStateOf(emptyList<ContextSet>()) }
    var tags by remember { mutableStateOf(emptyList<Tag>()) }
    var scales by remember { mutableStateOf(emptyList<FeelingScale>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ExperienceChoice>?>(null) }
    var searchName by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<ContextSet?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (invalid: IllegalArgumentException) { error = invalid.message }
            catch (_: Exception) { error = "情境组合读写失败，请重试。" }
            finally { busy = false }
        }
    }
    suspend fun reload() {
        sets = store.contextSets.list()
        tags = store.listTags()
        scales = store.listFeelingScales()
        loaded = true
    }
    LaunchedEffect(Unit) { run { reload() } }
    val matches = results
    val title = when { edit -> if (original == null) "新建组合" else "编辑组合"
        matches != null -> searchName
        else -> "情境组合" }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (busy) LinearProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                if (!loaded) TextButton(onClick = { run { reload() } }) { Text("重新读取") } }
            if (edit) {
                key(original?.row?.id) {
                    ContextSetForm(original, tags, scales, model, !busy) { name, selected, feelings, stamp ->
                        run {
                            store.contextSets.save(original?.row?.id, name, selected, feelings, stamp)
                            edit = false
                            loaded = false
                            reload()
                        }
                    }
                }
            } else if (matches != null) {
                if (matches.isEmpty()) Text("没有同时满足组合条件的记录。")
                for (record in matches) TextButton(onClick = { close(); model.openRecord(RecordId(record.id)) }) { Text(record.label) }
            } else if (loaded) {
                TextButton(enabled = !busy, onClick = { original = null; edit = true; error = null }) { Text("新建组合") }
                for (set in sets) Column {
                    Row {
                        tags.find { it.id.value == set.row.stampTag }?.let { TagStampIcon(it, model) }
                        Text(set.row.name, style = MaterialTheme.typography.titleMedium)
                    }
                    Text((set.tags.map { id -> tags.find { it.id.value == id }?.name ?: "失效标签" } +
                        set.feelings.map { choice -> (scales.find { it.id == choice.scaleId }?.name ?: "失效项目") + " · " + choice.display }).joinToString("、"))
                    FlowRow {
                        if (editingDraft) TextButton(modifier = Modifier.testTag("context-apply-${set.row.id}"), enabled = !busy, onClick = { run { store.contextSets.ensureAvailable(set.row.id); model.applyContextSet(set.row.id); close() } }) { Text("套用到草稿") }
                        else TextButton(modifier = Modifier.testTag("context-matches-${set.row.id}"), enabled = !busy, onClick = { run { results = store.contextSets.matches(set.row.id); searchName = set.row.name } }) { Text("查找记录") }
                        TextButton(enabled = !busy, onClick = { original = set; edit = true; error = null }) { Text("编辑组合") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        when { edit -> edit = false; matches != null -> results = null; else -> close() }
        error = null
    }) { Text(if (edit || matches != null) "返回组合" else "关闭") } })
}

@Composable
private fun ContextSetForm(original: ContextSet?, tags: List<Tag>, scales: List<FeelingScale>, model: FolioViewModel,
    enabled: Boolean, save: (String, List<String>, List<FeelingChoice>, Tag?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.row?.name.orEmpty()) }
    var selected by remember { mutableStateOf(original?.tags.orEmpty()) }
    var choices by remember { mutableStateOf(original?.feelings.orEmpty()) }
    var stamp by remember { mutableStateOf(tags.find { it.id.value == original?.row?.stampTag }) }
    var pickingStamp by remember { mutableStateOf(false) }
    var pickingScale by remember { mutableStateOf<FeelingScale?>(null) }
    OutlinedTextField(name, { name = it }, label = { Text("组合名称") }, enabled = enabled)
    Text("标签")
    if (tags.isEmpty()) Text("完成带标签的记录后，可在这里选择标签。")
    for (id in selected.filter { id -> tags.none { it.id.value == id } }) {
        TextButton(onClick = { selected = selected - id }) { Text("移除失效标签") }
    }
    FlowRow { for (tag in tags) FilterChip(tag.id.value in selected, {
        selected = if (tag.id.value in selected) selected - tag.id.value else selected + tag.id.value
    }, label = { Text(tag.name) }, enabled = enabled) }
    if (choices.isNotEmpty() || scales.isNotEmpty()) Text("感受程度")
    for (value in choices) TextButton(onClick = { choices = choices - value }, enabled = enabled) {
        Text("移除 " + (scales.find { it.id == value.scaleId }?.name ?: "失效项目") + " · " + value.display)
    }
    FlowRow { for (s in scales) TextButton(onClick = { pickingScale = s }, enabled = enabled) { Text(s.name) } }
    pickingScale?.let { s ->
        fun select(choice: FeelingChoice) { choices = choices.filterNot { it.scaleId == choice.scaleId } + choice; pickingScale = null }
        FlowRow { s.levels.forEachIndexed { index, label -> TextButton(onClick = { select(s.choice(index + 1)) }) { Text(label) } } }
        key(s.id) {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, label = { Text("自定程度") })
            TextButton(enabled = text.isNotBlank(), onClick = { select(FeelingChoice(s.id, 0, 0, text.trim())) }) { Text("选用文字程度") }
        }
    }
    val stamps = tags.filter { it.stampRevision != null }
    if (stamps.isNotEmpty()) {
        TextButton(onClick = { pickingStamp = !pickingStamp }) { Text("组合图章 · " + (stamp?.name ?: "选填")) }
        if (pickingStamp) {
            TextButton(onClick = { stamp = null; pickingStamp = false }) { Text("不配图") }
            FlowRow { for (tag in stamps) TextButton(onClick = { stamp = tag; pickingStamp = false }) { TagStampIcon(tag, model); Text(tag.name) } }
        }
    }
    Button(enabled = enabled && name.isNotBlank() && (selected.isNotEmpty() || choices.isNotEmpty()),
        onClick = { save(name, selected, choices, stamp) }) { Text("保存组合") }
}
