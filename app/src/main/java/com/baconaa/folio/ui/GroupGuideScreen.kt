package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import java.util.Collections
import java.util.UUID

internal fun RecordCard.guideLabel(): String = title ?: when (val item = preview.items.firstOrNull()) {
    is PreviewContentItem.Text -> item.text
    is PreviewContentItem.Pdf -> item.displayName
    is PreviewContentItem.Video -> item.displayName
    is PreviewContentItem.Photo -> item.caption.ifBlank { "照片" }
    is PreviewContentItem.Audio -> item.caption.ifBlank { "录音" }
    is PreviewContentItem.Checklist -> item.entries.firstOrNull()?.text ?: "清单"
    null -> "记录"
}

@Composable
internal fun GroupGuideScreen(model: FolioViewModel, guide: GroupGuideContent, scroll: LazyListState) {
    val cards = remember(guide) { guide.content.members.associateBy { it.id } }
    val editor = model.state.guideEdit
    if (editor != null) {
        GuideEditor(model, editor, cards)
        return
    }
    val layout = guide.layout
    val members = remember(layout) { layout.members.groupBy { it.sectionId } }
    LazyColumn(Modifier.fillMaxSize().testTag("guide-reading"), state = scroll,
        contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(guide.content.group.name, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = model::beginGuideEdit, enabled = !model.state.busy) { Text("编辑导览") }
            if (layout.description.isNotBlank()) Text(layout.description)
        }
        layout.sections.forEachIndexed { index, section ->
            item(key = "section-${section.id.value}") {
                Text(section.label(index), style = MaterialTheme.typography.titleMedium)
                if (section.description.isNotBlank()) Text(section.description)
                if (members[section.id].isNullOrEmpty()) Text("这段还没有记录。")
            }
            items(members[section.id].orEmpty(), key = { "record-${it.recordId.value}" }) { member ->
                RecordPreview(cards.getValue(member.recordId), model)
            }
        }
        val unassigned = members[null].orEmpty()
        if (unassigned.isNotEmpty()) {
            item { Text("未分段", style = MaterialTheme.typography.titleMedium) }
            items(unassigned, key = { "record-${it.recordId.value}" }) { member -> RecordPreview(cards.getValue(member.recordId), model) }
        }
        if (layout.members.isEmpty()) item { Text("组内还没有记录，可返回记录组收录。") }
    }
}

private fun GuideSection.label(index: Int) = title.ifBlank { "第 ${index + 1} 段" }

@Composable
private fun GuideEditor(model: FolioViewModel, editor: GuideEditing, cards: Map<RecordId, RecordCard>) {
    val draft = editor.layout
    val enabled = !model.state.busy
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var replaceDialog by rememberSaveable { mutableStateOf(false) }
    val peers = remember(draft.members) { draft.members.groupBy { it.sectionId } }
    Column(Modifier.fillMaxSize()) {
        if (editor.conflicting != null) TextButton(onClick = { replaceDialog = true }) { Text("载入最新导览") }
        LazyColumn(Modifier.fillMaxSize().testTag("guide-editor"), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(draft.description, { text -> model.changeGuide { it.copy(description = text) } },
                    label = { Text("整组说明") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("guide-description"))
            }
            itemsIndexed(draft.sections, key = { _, section -> "section-${section.id.value}" }) { index, section ->
                val id = section.id
                Column(Modifier.testTag("guide-edit-section-${id.value}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(section.title, { text -> model.changeGuide { layout ->
                        layout.editSection(id) { it.copy(title = text) }
                    } }, singleLine = true, label = { Text("第 ${index + 1} 段标题") }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("guide-title-${id.value}"))
                    OutlinedTextField(section.description, { text -> model.changeGuide { layout ->
                        layout.editSection(id) { it.copy(description = text) }
                    } }, label = { Text("分段说明") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("guide-note-${id.value}"))
                    FlowRow {
                        TextButton(onClick = { model.changeGuide { it.moveSection(id, -1) } }, enabled = enabled && index > 0) { Text("上移分段") }
                        TextButton(onClick = { model.changeGuide { it.moveSection(id, 1) } }, enabled = enabled && index < draft.sections.lastIndex) { Text("下移分段") }
                        TextButton(onClick = { deleting = id.value }, enabled = enabled) { Text("删除分段") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { model.changeGuide { it.copy(sections = it.sections + GuideSection(GuideSectionId(UUID.randomUUID().toString()))) } },
                    enabled = enabled) { Text("添加分段") }
            }
            if (draft.members.isNotEmpty()) item { Text("成员分段与段内顺序", style = MaterialTheme.typography.titleMedium) }
            items(draft.members, key = { "record-${it.recordId.value}" }) { member ->
                val card = cards.getValue(member.recordId)
                val sameSection = peers.getValue(member.sectionId)
                Column(Modifier.fillMaxWidth().testTag("guide-edit-member-${card.id.value}")) {
                    RecordCardSummary(card)
                    FlowRow {
                        SectionPicker(member, draft.sections, enabled) { section ->
                            model.changeGuide { layout ->
                                layout.copy(members = layout.members.map { if (it.recordId == card.id) it.copy(sectionId = section) else it })
                            }
                        }
                        TextButton(onClick = { model.changeGuide { it.moveMember(card.id, -1) } },
                            enabled = enabled && member != sameSection.first()) { Text("段内上移") }
                        TextButton(onClick = { model.changeGuide { it.moveMember(card.id, 1) } },
                            enabled = enabled && member != sameSection.last()) { Text("段内下移") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    val removing = draft.sections.indexOfFirst { it.id.value == deleting }
    if (removing >= 0) {
        val section = draft.sections[removing]
        val count = peers[section.id].orEmpty().size
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${section.label(removing)}」？") },
            text = { Text("组内 ${count} 条可见记录将移至未分段区，保存导览后生效。") },
            confirmButton = { TextButton(onClick = {
                model.changeGuide { it.copy(sections = it.sections.filterNot { s -> s.id == section.id },
                    members = it.members.map { m -> if (m.sectionId == section.id) m.copy(sectionId = null) else m }) }
                deleting = null
            }) { Text("确认删除分段") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (replaceDialog) AlertDialog(onDismissRequest = { replaceDialog = false }, title = { Text("载入最新导览？") },
        text = { Text("将用最近保存的分段和说明替换当前未保存的修改。") },
        confirmButton = { TextButton(onClick = { replaceDialog = false; model.useLatestGuide() }) { Text("确认载入") } },
        dismissButton = { TextButton(onClick = { replaceDialog = false }) { Text("保留当前输入") } })
}

@Composable
private fun SectionPicker(member: GuideMember, sections: List<GuideSection>, enabled: Boolean, select: (GuideSectionId?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val index = sections.indexOfFirst { it.id == member.sectionId }
    Box {
        OutlinedButton(onClick = { focus.clearFocus(); open = true }, enabled = enabled,
            modifier = Modifier.testTag("guide-assign-${member.recordId.value}")) {
            Text(if (index < 0) "未分段 ▾" else "${sections[index].label(index)} ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("guide-section-menu")) {
            DropdownMenuItem(text = { Text("未分段") }, onClick = { open = false; select(null) })
            sections.forEachIndexed { i, section ->
                DropdownMenuItem(text = { Text(section.label(i)) }, onClick = { open = false; select(section.id) })
            }
        }
    }
}

private fun GuideLayout.editSection(id: GuideSectionId, change: (GuideSection) -> GuideSection) =
    copy(sections = sections.map { if (it.id == id) change(it) else it })

private fun GuideLayout.moveSection(id: GuideSectionId, step: Int): GuideLayout {
    val from = sections.indexOfFirst { it.id == id }
    val to = from + step
    if (from < 0 || to !in sections.indices) return this
    return copy(sections = sections.toMutableList().apply { Collections.swap(this, from, to) })
}

private fun GuideLayout.moveMember(id: RecordId, step: Int): GuideLayout {
    val from = members.indexOfFirst { it.recordId == id }
    if (from < 0) return this
    val range = if (step > 0) (from + 1)..members.lastIndex else (from - 1) downTo 0
    val to = range.firstOrNull { members[it].sectionId == members[from].sectionId } ?: return this
    return copy(members = members.toMutableList().apply { Collections.swap(this, from, to) })
}
