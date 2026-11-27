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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface FeelingTarget {
    data class Draft(val id: DraftId) : FeelingTarget
    data class Place(val id: PlaceId) : FeelingTarget
    data object Filter : FeelingTarget
    data object Template : FeelingTarget
}

private data class FeelingOptions(val scales: List<FeelingScale>, val values: List<FeelingChoice>,
    val suggested: Set<ScaleId>, val history: List<FeelingChoice>, val impression: String)

private suspend fun FeelingScales.options(target: FeelingTarget, selected: ScaleId?): FeelingOptions {
    val draft = (target as? FeelingTarget.Draft)?.let { draftFeelingState(it.id) }
    val place = target as? FeelingTarget.Place
    return FeelingOptions(listFeelingScales(), draft?.choices ?: place?.let { placeFeelings(it.id) }.orEmpty(),
        draft?.suggested.orEmpty(), if (target == FeelingTarget.Filter && selected != null) historicalFeelings(selected) else emptyList(),
        place?.let { placeImpression(it.id) }.orEmpty())
}

@Composable
internal fun FeelingEntry(model: FolioViewModel, target: FeelingTarget, text: String, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text(text) }
    if (open) FeelingDialog(model, target) { open = false }
}

@Composable
internal fun FeelingDialog(model: FolioViewModel, target: FeelingTarget, close: () -> Unit) {
    val store = model.feelingScales
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var options by remember(target, selected, revision) { mutableStateOf<FeelingOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable(selected) { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(target, selected, revision) {
        error = null
        try { options = withContext(Dispatchers.IO) { requireNotNull(store).options(target, selected?.let(::ScaleId)) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "感受刻度读取失败，请重试。" }
    }
    fun save(action: suspend FeelingScales.() -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(store).action() }; revision++ }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.localizedMessage ?: "保存失败，请重试。" }
            finally { busy = false }
        }
    }
    val data = options
    val scale = data?.scales?.find { it.id.value == selected }
    val template = model.state.templateLibrary?.edit
    val title = when (target) {
        is FeelingTarget.Place -> "地点整体印象"
        is FeelingTarget.Draft -> "本次感受"
        FeelingTarget.Filter -> "按感受查找"
        FeelingTarget.Template -> "模板使用的刻度"
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(scale?.name ?: title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().testTag("feeling-options"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (busy || (data == null && error == null)) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { item { ErrorMessage(it) { revision++ } } }
                if (data == null) return@LazyColumn
                if (scale == null) {
                    if (target is FeelingTarget.Place) item {
                        OutlinedTextField(note ?: data.impression, { note = it }, label = { Text("地点印象") })
                        TextButton(enabled = !busy && note != null && note != data.impression,
                            onClick = { val value = note.orEmpty(); save { savePlaceImpression(target.id, value) } }) { Text("保存地点印象") }
                    }
                    if (target != FeelingTarget.Filter) item {
                        TextButton(onClick = { editing = true }, enabled = !busy) { Text("新建刻度") }
                    }
                    if (data.scales.isEmpty()) item { Text("尚无感受刻度。") }
                    items(data.scales.sortedByDescending { it.id in data.suggested }, key = { it.id.value }) { project ->
                        val value = data.values.find { it.scaleId == project.id }
                        val identity = if (data.scales.count { it.name == project.name } > 1) " · ${project.id.value.take(8)}" else ""
                        val caption = when {
                            value != null -> value.display
                            project.id in data.suggested -> "模板选填"
                            else -> "${project.levels.size} 级 · ${project.levels.first()} 至 ${project.levels.last()}"
                        }
                        val prefix = if (target == FeelingTarget.Template && project.id in template?.scales.orEmpty()) "已选 · " else ""
                        TextButton(onClick = {
                            if (target == FeelingTarget.Template && template != null) {
                                val next = if (project.id in template.scales) template.scales - project.id else template.scales + project.id
                                model.changeTemplate(template.copy(scales = next))
                            } else selected = project.id.value
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("feeling-scale-${project.id.value}")) {
                            Text("$prefix${project.name}$identity · $caption")
                        }
                    }
                } else {
                    val current = data.values.find { it.scaleId == scale.id }
                    fun choose(choice: FeelingChoice) {
                        when (target) {
                            is FeelingTarget.Draft -> save { setDraftFeeling(target.id, choice) }
                            is FeelingTarget.Place -> save { setPlaceFeeling(target.id, choice) }
                            FeelingTarget.Filter -> { model.filterFeeling(NamedFeeling(scale.name, choice)); close() }
                            FeelingTarget.Template -> Unit
                        }
                    }
                    current?.let { item { Text("当前 · ${it.display}") } }
                    val choices = if (target == FeelingTarget.Filter) data.history else scale.levels.indices.map { scale.choice(it + 1) }
                    if (choices.isEmpty()) item { Text("尚无正式记录填写这个刻度。") }
                    items(choices) { choice ->
                        TextButton(onClick = { choose(choice) }, enabled = !busy) { Text(choice.display) }
                    }
                    if (target != FeelingTarget.Filter) item {
                        OutlinedTextField(custom, { custom = it }, label = { Text("自定义感受") })
                        TextButton(onClick = { choose(FeelingChoice(scale.id, 0, 0, custom.trim())) }, enabled = custom.isNotBlank() && !busy) {
                            Text("采用自定义文字")
                        }
                        if (current != null) TextButton(onClick = {
                            when (target) {
                                is FeelingTarget.Draft -> save { removeDraftFeeling(target.id, scale.id) }
                                is FeelingTarget.Place -> save { removePlaceFeeling(target.id, scale.id) }
                                else -> Unit
                            }
                        }, enabled = !busy) { Text("清除本项感受") }
                        TextButton(onClick = { editing = true }, enabled = !busy) { Text("修改共享刻度") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = !busy) { Text("完成") } },
        dismissButton = { if (selected != null) TextButton(onClick = { selected = null }, enabled = !busy) { Text("返回项目") } })
    if (editing) ScaleDefinitionDialog(scale, store, onDismiss = { editing = false }, onSaved = {
        editing = false
        if (target != FeelingTarget.Template) selected = it.id.value
        revision++
    })
}

@Composable
private fun ScaleDefinitionDialog(previous: FeelingScale?, store: FeelingScales?, onDismiss: () -> Unit, onSaved: (FeelingScale) -> Unit) {
    var name by rememberSaveable { mutableStateOf(previous?.name.orEmpty()) }
    var levels by rememberSaveable { mutableStateOf(previous?.levels?.joinToString("\n").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (previous == null) "新建共享刻度" else "修改共享刻度") },
        text = { LazyColumn {
            item { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("刻度名称") }) }
            item { OutlinedTextField(levels, { levels = it }, label = { Text("等级含义，每行一级") }, minLines = 2, maxLines = 6) }
            if (previous != null) item { Text("新等级用于此后填写，已保存的程度会保留当时的含义。") }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && levels.lines().all { it.isNotBlank() }, onClick = {
            busy = true
            scope.launch {
                try { onSaved(withContext(Dispatchers.IO) { requireNotNull(store).saveFeelingScale(previous, name, levels.lines()) }) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.localizedMessage ?: "保存失败，请重试。" }
                finally { busy = false }
            }
        }) { Text("保存刻度") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
