package com.baconaa.folio.ui

import androidx.compose.foundation.clickable
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

@Composable
internal fun PlaceDescription(place: Place) {
    Text(place.info.name, style = MaterialTheme.typography.titleMedium)
    listOf(place.info.city, place.info.address, place.info.note).filter { it.isNotEmpty() }.forEach { Text(it, fontSize = 12.sp) }
    place.info.coordinates?.let { Text(it.label(), fontSize = 12.sp) }
}

@Composable
internal fun PlacesScreen(model: FolioViewModel, scroll: androidx.compose.foundation.lazy.LazyListState) {
    val state = model.state
    Column(Modifier.padding(horizontal = 20.dp)) {
        PlaceSearch(model)
        Text("记录条数不代表到访次数；没有坐标也能查看。", fontSize = 12.sp)
        if (!state.placesLoading && state.placesError == null && state.places.isEmpty()) Text("没有匹配的地点。可在记录编辑中添加地点。")
        LazyColumn(Modifier.testTag("places-list"), state = scroll) {
            if (state.placesError == null) items(state.places, key = { it.place.id.value }) { summary ->
                Column(Modifier.fillMaxWidth().testTag("place-open-${summary.place.id.value}").clickable { model.openPlace(summary.place) }.padding(vertical = 16.dp)) {
                    PlaceDescription(summary.place)
                    Text("${summary.recordCount} 条记录", fontSize = 12.sp)
                    Text(when (val recent = summary.recentExperience) {
                        RecentExperience.Empty -> "暂无经历"
                        is RecentExperience.Known -> "最近经历：${recent.eventTime.label()}"
                        is RecentExperience.Uncertain -> if (recent.undatedCount > 0) "最近经历待确定 · 含 ${recent.undatedCount} 条时间未定" else "最近经历待确定 · 时间无法确定先后"
                    }, fontSize = 12.sp)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlaceSearch(model: FolioViewModel) {
    val state = model.state
    OutlinedTextField(state.placeQuery, model::loadPlaces, label = { Text("查找地点") },
        supportingText = { Text("名称、城市、地址或区别备注") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("place-search"))
    if (state.placesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.placesError?.let { ErrorMessage(it) { model.loadPlaces(state.placeQuery) } }
}

@Composable
internal fun PlacePicker(model: FolioViewModel, close: () -> Unit) {
    val state = model.state
    val selected = state.editor?.place
    val enabled = !state.busy && state.editor?.needsReload != true
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("选择地点") }, text = {
        Column {
            PlaceSearch(model)
            selected?.let {
                Text("当前选择：${it.info.name}${if (it.isDraft) "（本草稿新地点）" else ""}")
                if (it.isDraft) TextButton(onClick = { form = "edit" }, enabled = enabled) { Text("修改新地点资料") }
            }
            if (state.editor?.needsReload == true) ErrorMessage("地点已保存，请重试读取。", model::reload)
            else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 240.dp).testTag("place-choices")) {
                if (state.placesError == null) items(state.places, key = { it.place.id.value }) { summary ->
                    Row(Modifier.fillMaxWidth()) {
                        RadioButton(selected?.id == summary.place.id, { model.selectPlace(summary.place) }, enabled = enabled,
                            modifier = Modifier.testTag("place-choice-${summary.place.id.value}"))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) { PlaceDescription(summary.place) }
                    }
                }
            }
            Row {
                TextButton(onClick = { form = "new" }, enabled = enabled) { Text("新建地点") }
                TextButton(onClick = { model.selectPlace(null) }, enabled = enabled && selected != null) { Text("取消地点") }
            }
            Text("地点选择随记录完成生效；共享资料从地点小册独立修改。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭地点") } })
    if (form != null) {
        val creating = form == "new"
        PlaceInfoDialog(model, if (creating) null else selected, shared = false, onDismiss = { form = null },
            save = { if (creating) model.createPlace(it) else model.updatePrivatePlace(it) })
    }
}

@Composable
internal fun PlaceBookHeader(model: FolioViewModel) {
    val state = model.state
    val place = state.filterPlace ?: return
    var editing by rememberSaveable(place.id.value) { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    Column {
        Row {
            Text(place.info.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(onClick = { actions = true }, enabled = !state.busy) { Text("地点操作") }
                DropdownMenu(actions, { actions = false }) {
                    DropdownMenuItem(text = { Text("在这里记一条") }, onClick = { actions = false; model.newTextAtPlace() })
                    DropdownMenuItem(text = { Text("关联已有记录") }, onClick = { actions = false; model.chooseExistingRecord() })
                    DropdownMenuItem(text = { Text("编辑地点资料") }, onClick = { actions = false; editing = true })
                }
            }
        }
        val details = listOf(place.info.city, place.info.address, place.info.note).filter { it.isNotEmpty() }.joinToString(" · ")
        if (details.isNotEmpty()) Text(details, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        place.info.coordinates?.let { Text(it.label(), fontSize = 12.sp) }
        TextButton(onClick = model::clearPlaceFilter) { Text("移除地点条件 · 搜索全部记录") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(!state.placeRecent, { model.selectPlaceRecent(false) }, label = { Text("事件时间") })
            FilterChip(state.placeRecent, { model.selectPlaceRecent(true) }, label = { Text("最近记录") })
        }
    }
    if (editing) PlaceInfoDialog(model, place, shared = true, onDismiss = { editing = false }, save = model::updatePlace)
}

@Composable
private fun PlaceInfoDialog(model: FolioViewModel, initial: Place?, shared: Boolean, onDismiss: () -> Unit, save: (PlaceInfo) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.info?.name.orEmpty()) }
    var city by rememberSaveable { mutableStateOf(initial?.info?.city.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(initial?.info?.address.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.info?.note.orEmpty()) }
    var latitude by rememberSaveable { mutableStateOf(initial?.info?.coordinates?.latitude) }
    var longitude by rememberSaveable { mutableStateOf(initial?.info?.coordinates?.longitude) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val state = model.state
    val current = if (shared) state.filterPlace else state.editor?.place
    val coordinates = latitude?.let { lat -> longitude?.let { PlaceCoordinates(lat, it) } }
    val info = PlaceInfo(name.trim(), city.trim(), address.trim(), note.trim(), coordinates)
    LaunchedEffect(state.busy, current, submitted) {
        if (submitted && !state.busy && state.error == null && current?.info == info) onDismiss()
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }, title = { Text(if (shared) "编辑地点资料" else "新地点资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (shared) "保存后同步所有引用此地点的记录；不会提交记录草稿。" else "同名地点可以分别保存，城市、地址和备注均可留空。")
            OutlinedTextField(name, { name = it }, label = { Text("地点名称") }, enabled = !state.busy, modifier = Modifier.testTag("place-name"))
            OutlinedTextField(city, { city = it }, label = { Text("城市（选填）") }, enabled = !state.busy, modifier = Modifier.testTag("place-city"))
            OutlinedTextField(address, { address = it }, label = { Text("地址（选填）") }, enabled = !state.busy, modifier = Modifier.testTag("place-address"))
            OutlinedTextField(note, { note = it }, label = { Text("区别备注（选填）") }, enabled = !state.busy, modifier = Modifier.testTag("place-note"))
            PlaceCoordinatesField(coordinates, !state.busy) { latitude = it?.latitude; longitude = it?.longitude }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.editor?.needsReload == true) ErrorMessage("地点已保存，请重试读取。", model::reload)
        }
    }, confirmButton = { TextButton(onClick = { submitted = true; save(info) },
        enabled = name.isNotBlank() && !state.busy && state.editor?.needsReload != true) { Text("保存地点") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") } })
}
