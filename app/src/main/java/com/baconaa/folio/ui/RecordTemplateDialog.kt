package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*

data class TemplateEdit(val previous: RecordTemplate?, val name: String, val fields: List<TemplateField>, val tags: Set<TagId>)
data class TemplateLibrary(val saved: List<RecordTemplate>? = null, val edit: TemplateEdit? = null, val stale: Boolean = false, val justSaved: Boolean = false)

@Composable
internal fun TemplateLibraryDialog(library: TemplateLibrary, model: FolioViewModel) {
    val state = model.state
    val form = library.edit
    val enabled = !state.busy && !library.stale
    AlertDialog(onDismissRequest = model::dismissTemplates, title = { Text(when { form != null -> "编辑模板"; library.justSaved -> "模板已保存"; else -> "记录模板" }) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().testTag("template-options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (library.stale || (form == null && library.saved == null)) {
                        TextButton(onClick = model::openTemplates, enabled = !state.busy) { Text("重新读取模板") }
                    }
                } }
                if (form == null) {
                    val saved = library.saved.orEmpty()
                    if (library.saved != null && saved.isEmpty()) item { Text("还没有模板。") }
                    items(saved, key = { it.id.value }) { template ->
                        Column {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            template.fields.forEachIndexed { index, field -> Text("${index + 1}. ${field.name} · ${field.type.label}") }
                            if (template.tags.isNotEmpty()) Text(template.tags.joinToString(" · ") { it.name })
                            FlowRow {
                                Button(onClick = { model.useTemplate(template) }, enabled = enabled,
                                    modifier = Modifier.testTag("use-template-${template.id.value}")) { Text("按模板起草") }
                                TextButton(onClick = { model.editTemplate(template) }, enabled = enabled,
                                    modifier = Modifier.testTag("edit-template-${template.id.value}")) { Text("修改模板") }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    item {
                        OutlinedTextField(form.name, { model.changeTemplate(form.copy(name = it)) }, singleLine = true,
                            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("template-name"), label = { Text("模板名称") })
                    }
                    itemsIndexed(form.fields) { index, field ->
                        var types by remember { mutableStateOf(false) }
                        fun replace(value: TemplateField) = model.changeTemplate(form.copy(fields = form.fields.toMutableList().apply { set(index, value) }))
                        fun move(to: Int) = model.changeTemplate(form.copy(fields = form.fields.toMutableList().apply { add(to, removeAt(index)) }))
                        Column {
                            OutlinedTextField(field.name, { replace(field.copy(name = it)) }, singleLine = true, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("template-field-$index"), label = { Text("字段 ${index + 1} 名称") })
                            Box {
                                TextButton(onClick = { types = true }, enabled = enabled) { Text("类型 · ${field.type.label}") }
                                DropdownMenu(types, onDismissRequest = { types = false }) {
                                    TemplateFieldType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.label) }, onClick = {
                                        types = false
                                        replace(TemplateField(type, if (field.name == field.type.label) type.label else field.name))
                                    }) }
                                }
                            }
                            FlowRow {
                                TextButton(onClick = { move(index - 1) }, enabled = enabled && index > 0,
                                    modifier = Modifier.testTag("template-up-$index")) { Text("上移") }
                                TextButton(onClick = { move(index + 1) }, enabled = enabled && index < form.fields.lastIndex) { Text("下移") }
                                TextButton(onClick = { model.changeTemplate(form.copy(fields = form.fields.filterIndexed { i, _ -> i != index })) },
                                    enabled = enabled) { Text("移除") }
                            }
                        }
                    }
                    item {
                        FlowRow {
                            TemplateFieldType.entries.forEach { type ->
                                TextButton(onClick = { model.changeTemplate(form.copy(fields = form.fields + TemplateField(type))) },
                                    enabled = enabled) { Text("添加${type.label}") }
                            }
                        }
                    }
                    if (state.tagsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.tagsError != null) item { ErrorMessage(state.tagsError, model::loadTags) }
                    if (state.sharedTags.isNotEmpty()) item { Text("沿用共享标签") }
                    items(state.sharedTags, key = { it.id.value }) { tag ->
                        val selected = tag.id in form.tags
                        Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox) {
                            model.changeTemplate(form.copy(tags = if (it) form.tags + tag.id else form.tags - tag.id))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected, onCheckedChange = null)
                            Text(tag.name, Modifier.padding(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (library.justSaved && form == null) TextButton(onClick = model::openTemplates, enabled = enabled) { Text("全部模板") }
            else if (form == null) TextButton(onClick = { model.editTemplate() }, enabled = enabled) { Text("新建模板") }
            else TextButton(onClick = model::saveTemplate, enabled = enabled && form.name.isNotBlank() &&
                form.fields.isNotEmpty() && form.fields.none { it.name.isBlank() }) { Text("保存模板") }
        },
        dismissButton = { TextButton(onClick = model::dismissTemplates, enabled = !state.busy) { Text("关闭") } })
}
