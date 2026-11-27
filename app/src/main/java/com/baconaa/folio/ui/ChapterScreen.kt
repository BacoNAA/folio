package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ChapterList(model: FolioViewModel, scroll: LazyListState) {
    var newId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(state = scroll) {
        item { Button(onClick = { newId = UUID.randomUUID().toString() }) { Text("新建章节") } }
        if (model.state.groups.isEmpty()) item { Text("还没有生活章节。", Modifier.padding(vertical = 16.dp)) }
        items(model.state.groups, key = { it.id.value }) { chapter ->
            TextButton(onClick = { model.openGroup(chapter.id) }, modifier = Modifier.fillMaxWidth()) { Text(chapter.name) }
        }
    }
    newId?.let { ChapterEditor(model, RecordGroupId(it), null) { newId = null } }
}

@Composable
internal fun ChapterSummary(profile: ChapterProfile, cover: ChapterPhoto?) {
    cover?.let { Photo(it.photo, thumbnail = true, thumbnailDescription = "章节封面") }
    if (profile.cover != null && cover == null) Text("封面来源已不可用，可以重新选择。")
    val period = when {
        profile.start.isNotEmpty() && profile.end.isNotEmpty() -> "${profile.start} 至 ${profile.end}"
        profile.start.isNotEmpty() -> "${profile.start} 起"
        profile.end.isNotEmpty() -> "截至 ${profile.end}"
        else -> ""
    }
    if (period.isNotEmpty()) Text(period)
    if (profile.note.isNotEmpty()) Text(profile.note)
}

@Composable
internal fun ChapterEditor(model: FolioViewModel, id: RecordGroupId, original: ChapterProfile?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(original?.start.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(original?.end.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    var record by rememberSaveable { mutableStateOf(original?.cover?.record?.value) }
    var item by rememberSaveable { mutableStateOf(original?.cover?.item?.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<ChapterPhoto>?>(null) }
    val cover = record?.let { r -> item?.let { ChapterCover(RecordId(r), ItemId(it)) } }
    if (photos != null) {
        AlertDialog(onDismissRequest = { photos = null }, title = { Text("选择章节封面") },
            text = { LazyColumn { val choices = photos.orEmpty()
                if (choices.isEmpty()) item { Text("正式记录中还没有照片。") }
                items(choices, key = { it.cover.record.value + "/" + it.cover.item.value }) { photo ->
                    TextButton(onClick = {
                        record = photo.cover.record.value
                        item = photo.cover.item.value
                        photos = null
                    }) {
                        Photo(photo.photo, true, "封面候选")
                        Text(photo.label, Modifier.padding(start = 8.dp))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { photos = null }) { Text("返回") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (original == null) "新建生活章节" else "编辑章节") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("章节名称") }, singleLine = true)
            OutlinedTextField(start, { start = it }, label = { Text("起点（可选）") }, placeholder = { Text("2024、2024-06 或具体日期") }, singleLine = true)
            OutlinedTextField(end, { end = it }, label = { Text("终点（可选）") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("一句说明（可选）") })
            Row {
                TextButton(onClick = { busy = true; scope.launch {
                    try { photos = model.chapterPhotos(); error = null }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "读取照片失败，请重试。" }
                    finally { busy = false }
                } }, enabled = !busy) { Text(if (cover == null) "选择封面" else "更换封面") }
                if (cover != null) TextButton(onClick = { record = null; item = null }, enabled = !busy) { Text("清除封面") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            val profile = ChapterProfile(name.trim(), start.trim(), end.trim(), note.trim(), cover)
            error = profile.inputError()
            if (error == null) {
                busy = true
                scope.launch {
                    try { model.saveChapter(id, original, profile); close() }
                    catch (e: CancellationException) { throw e }
                    catch (e: IllegalArgumentException) { error = e.message }
                    catch (_: Exception) { error = "保存章节失败，请重试。" }
                    finally { busy = false }
                }
            }
        }) { Text("保存章节") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}
