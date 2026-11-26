package com.baconaa.folio.ui

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
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException

data class PhotoReference(val record: RecordId, val item: ItemId)

data class PhotoComparison(val origin: Page, val detail: RecordId?, val detailOrigin: Page,
                           val left: PhotoReference?, val right: PhotoReference? = null)

@Composable
internal fun PhotoComparisonScreen(model: FolioViewModel) {
    val pair = model.state.photoComparison ?: return
    var choosingLeft by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(true, false).forEach { left ->
                val ref = if (left) pair.left else pair.right
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (left) "左侧照片" else "右侧照片", style = MaterialTheme.typography.titleSmall)
                    if (ref != null) key(ref) {
                        ReadLocalData(ref, { model.readSourceRecord(ref.record) }) { record ->
                            val item = record?.items?.filterIsInstance<ContentItem.Photo>()?.find { it.id == ref.item }
                            if (record == null || item == null) Text("来源照片已移除或在回收站中")
                            else {
                                Photo(item.photoId, strokes = item.strokes)
                                Text(record.title ?: record.text.take(40).ifBlank { "照片记录" })
                                Text(record.times.eventTime.label())
                                if (item.caption.isNotBlank()) Text(item.caption)
                                TextButton(onClick = { model.openRecord(ref.record) },
                                    modifier = Modifier.testTag(if (left) "compare-source-left" else "compare-source-right")) { Text("查看原记录") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { choosingLeft = left }, modifier = Modifier.testTag(if (left) "compare-left" else "compare-right")) {
                        Text(if (ref == null) "选择照片" else "换一张")
                    }
                }
            }
        }
    }
    choosingLeft?.let { left ->
        val scope = model.state.objectBook.takeIf { left && pair.origin == Page.ObjectBook }
        ComparisonPicker(model, scope, { choosingLeft = null }) { ref ->
            model.selectComparisonPhoto(left, ref) { choosingLeft = null }
        }
    }
}

@Composable
private fun ComparisonPicker(model: FolioViewModel, scope: RecordObject?, close: () -> Unit, select: (PhotoReference) -> Unit) {
    var recordId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(if (recordId == null) "选择照片来源" else "选择记录中的照片") }, text = {
        Column {
            model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val selected = recordId
            if (selected == null) {
                OutlinedTextField(search, { search = it }, label = { Text("查找有照片的记录") }, singleLine = true)
                ReadLocalData(search to scope?.id, { model.comparisonSources(search, scope) }) { records ->
                    if (records.isEmpty()) Text("没有符合条件的照片记录")
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(records, key = { it.id.value }) { record ->
                            TextButton(onClick = { recordId = record.id.value }, modifier = Modifier.testTag("photo-source-${record.id.value}")) {
                                Column(Modifier.fillMaxWidth()) { RecordCardSummary(record) }
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = { recordId = null }) { Text("重新选择记录") }
                ReadLocalData(selected, { model.readSourceRecord(RecordId(selected)) }) { record ->
                    val photos = record?.items?.filterIsInstance<ContentItem.Photo>().orEmpty()
                    if (photos.isEmpty()) Text("这条记录的照片已不可选")
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(photos, key = { it.id.value }) { photo ->
                            TextButton(onClick = { select(PhotoReference(RecordId(selected), photo.id)) },
                                modifier = Modifier.fillMaxWidth().testTag("compare-item-${photo.id.value}")) {
                                Photo(photo.photoId, thumbnail = true, thumbnailDescription = "候选照片", strokes = photo.strokes)
                                Text(photo.caption.ifBlank { "选择这张照片" }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
internal fun <T> ReadLocalData(key: Any?, read: suspend () -> T, error: String = "照片来源读取失败。", show: @Composable (T) -> Unit) {
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var result by remember(key, attempt) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key, attempt) {
        result = try { Result.success(read()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    val current = result
    when {
        current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        current.isFailure -> ErrorMessage(error) { attempt++ }
        else -> show(current.getOrThrow())
    }
}
