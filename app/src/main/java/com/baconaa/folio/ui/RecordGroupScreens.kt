package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.MoveDirection
import com.baconaa.folio.records.RecordGroupContent

@Composable
internal fun RecordGroupScreens(model: FolioViewModel, groupsScroll: LazyListState, membersScroll: LazyListState,
    pickerScroll: LazyListState, guideScroll: LazyListState) {
    val state = model.state
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        state.groupFeedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        state.groupReadError?.let {
            ErrorMessage(it, model::refresh)
            return@Column
        }
        when (state.page) {
            Page.Chapters -> ChapterList(model, groupsScroll)
            Page.Groups -> LazyColumn(state = groupsScroll, modifier = Modifier.testTag("group-list")) {
                item { Button(onClick = model::pickGroupMembers, enabled = !state.busy) { Text("多选记录") } }
                if (state.groups.isEmpty()) item { Text("还没有记录组。", Modifier.padding(vertical = 16.dp)) }
                items(state.groups, key = { it.id.value }) { group ->
                    TextButton(onClick = { model.openGroup(group.id) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("open-group-${group.id.value}")) { Text(group.name) }
                }
            }
            Page.GroupDetail -> state.groupContent?.let { GroupMembers(model, it, membersScroll) } ?: DeletedGroup(model)
            Page.GroupGuide -> state.groupGuide?.let { GroupGuideScreen(model, it, guideScroll) } ?: DeletedGroup(model)
            Page.GroupPicker -> GroupPicker(model, pickerScroll)
            Page.GroupDestination -> GroupDestination(model)
            else -> Unit
        }
    }
}

@Composable
private fun DeletedGroup(model: FolioViewModel) {
    Text("这个记录组已删除。")
    TextButton(onClick = { model.home(Page.Groups) }) { Text("返回组列表") }
}

@Composable
private fun GroupMembers(model: FolioViewModel, content: RecordGroupContent, scroll: LazyListState) {
    var deleteDialog by rememberSaveable(content.group.id.value) { mutableStateOf(false) }
    var editChapter by rememberSaveable(content.group.id.value) { mutableStateOf(false) }
    val enabled = !model.state.busy
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("group-members"),
        contentPadding = PaddingValues(vertical = 8.dp)) {
        content.chapter?.let { chapter ->
            item {
                ChapterSummary(chapter, content.cover)
                TextButton(onClick = { editChapter = true }, enabled = enabled) { Text("编辑章节") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::pickGroupMembers, enabled = enabled) { Text("收录记录") }
                if (content.chapter == null) TextButton(onClick = model::openGroupGuide, enabled = enabled) { Text("专题导览") }
                TextButton(onClick = { deleteDialog = true }, enabled = enabled) { Text(if (content.chapter != null) "删除章节" else "删除组") }
            }
        }
        if (content.members.isEmpty()) item { Text("组内还没有记录。", Modifier.padding(vertical = 16.dp)) }
        itemsIndexed(content.members, key = { _, card -> card.id.value }) { index, card ->
            RecordPreview(card, model) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.moveGroupMember(card.id, MoveDirection.UP) }, enabled = enabled && index > 0,
                        modifier = Modifier.testTag("group-up-${card.id.value}")) { Text("上移") }
                    TextButton(onClick = { model.moveGroupMember(card.id, MoveDirection.DOWN) }, enabled = enabled && index < content.members.lastIndex,
                        modifier = Modifier.testTag("group-down-${card.id.value}")) { Text("下移") }
                    TextButton(onClick = { model.removeGroupMember(card.id) }, enabled = enabled,
                        modifier = Modifier.testTag("group-remove-${card.id.value}")) { Text("移出") }
                }
            }
        }
    }
    if (editChapter) ChapterEditor(model, content.group.id, content.chapter) { editChapter = false }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
        title = { Text("删除「${content.group.name}」？") }, text = { Text("组内收录和顺序将一并移除，原记录仍可查看。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; model.deleteGroup() }) { Text(if (content.chapter != null) "确认删除章节" else "确认删除组") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable
private fun GroupPicker(model: FolioViewModel, scroll: LazyListState) {
    val state = model.state
    val selection = state.groupSelection ?: return
    val ranks = remember(selection.members) { selection.members.mapIndexed { index, id -> id to index + 1 }.toMap() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("group-pick-list"), state = scroll) {
            if (state.records.isEmpty()) item { Text("还没有可收录的正式记录。") }
            items(state.records, key = { it.id.value }) { record ->
                val rank = ranks[record.id]
                Row(Modifier.fillMaxWidth().testTag("group-pick-${record.id.value}")
                    .toggleable(value = rank != null, enabled = !state.busy, role = Role.Checkbox) { model.toggleGroupMember(record.id) }
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rank != null, onCheckedChange = null, enabled = !state.busy)
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rank != null) Text("选择顺序 $rank", style = MaterialTheme.typography.labelMedium)
                        RecordCardSummary(record)
                    }
                }
                HorizontalDivider()
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selection.members.size} 条")
            Button(onClick = {
                if (selection.origin == Page.GroupDetail) model.saveGroupSelection(state.groupId)
                else model.home(Page.GroupDestination)
            }, enabled = !state.busy && selection.members.isNotEmpty()) {
                Text(if (selection.origin == Page.GroupDetail) "确认收录" else "选择记录组")
            }
        }
    }
}

@Composable
private fun GroupDestination(model: FolioViewModel) {
    val state = model.state
    val selection = state.groupSelection ?: return
    LazyColumn(Modifier.fillMaxSize().testTag("group-destination"), contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("已选 ${selection.members.size} 条") }
        item { OutlinedTextField(selection.name, model::nameNewGroup, label = { Text("新组名") }, enabled = !state.busy,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group-name")) }
        item { Button(onClick = { model.saveGroupSelection(null) }, enabled = !state.busy && selection.name.isNotBlank()) { Text("新建并收录") } }
        if (state.groups.isNotEmpty()) item { Text("加入已有组", style = MaterialTheme.typography.titleMedium) }
        items(state.groups, key = { it.id.value }) { group ->
            OutlinedButton(onClick = { model.saveGroupSelection(group.id) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("join-group-${group.id.value}")) { Text(group.name) }
        }
    }
}
