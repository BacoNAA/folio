package com.baconaa.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.RecordObject
import com.baconaa.folio.records.TrackOwner

internal fun RecordObject.matchesObjectQuery(query: String) =
    (listOf(info.name, info.note) + info.aliases).any { it.contains(query.trim(), ignoreCase = true) }

@Composable
internal fun ObjectDirectory(model: FolioViewModel, query: String, changeQuery: (String) -> Unit, scroll: LazyListState) {
    val state = model.state
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, changeQuery, singleLine = true, label = { Text("查找名称、别名或区别说明") },
            modifier = Modifier.fillMaxWidth().testTag("object-directory-query"))
        when {
            state.objectsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.objectsError != null -> ErrorMessage(state.objectsError, model::loadObjects)
            else -> {
                val matches = state.sharedObjects.filter { it.matchesObjectQuery(query) }
                if (matches.isEmpty()) Text("没有匹配的人物或物件")
                LazyColumn(state = scroll) {
                    items(matches, key = { it.id.value }) { value ->
                        Column(Modifier.fillMaxWidth().clickable { model.openObject(value) }.padding(vertical = 12.dp)) {
                            Text(value.kind.label, style = MaterialTheme.typography.labelSmall)
                            ObjectDescription(value)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
internal fun ObjectBookScreen(model: FolioViewModel, scroll: LazyListState) {
    val state = model.state
    val book = state.objectBook ?: return
    var editing by rememberSaveable(book.id.value) { mutableStateOf(false) }
    LazyColumn(state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ObjectDescription(book)
            Row {
                TextButton(onClick = { editing = true }) { Text("编辑共享资料") }
                TextButton(onClick = { model.startPhotoComparison() }) { Text("照片对照") }
                TextButton(onClick = { model.openTrack(TrackOwner(book), book.info.name) }) { Text("变化轨道") }
            }
            OutlinedTextField(state.objectQuery, model::queryObjectBook, singleLine = true,
                label = { Text("在本小册中查找") }, modifier = Modifier.fillMaxWidth().testTag("book-query"))
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error == null) {
            if (!state.loading && state.objectRecords.isEmpty()) item { Text(if (state.objectQuery.isBlank()) "小册中还没有正式记录" else "本小册没有匹配的记录") }
            items(state.objectRecords, key = { it.id.value }) { record ->
                Column(Modifier.fillMaxWidth().clickable { model.openRecord(record.id) }.padding(vertical = 8.dp)) {
                    RecordCardSummary(record)
                }
                HorizontalDivider()
            }
        }
    }
    if (editing) ObjectInfoDialog(model, book.kind, book, { editing = false }) { model.updateObject(book, it) }
}
