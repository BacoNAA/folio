package com.baconaa.folio.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baconaa.folio.R
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.records.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFF7F4EE)
private val Ink = Color(0xFF423A32)
private val Accent = Color(0xFF956B50)

@Composable
fun FolioApp(model: FolioViewModel, launchMedia: (String, ItemId?) -> Unit = { _, _ -> }) {
    val state = model.state
    val recordsScroll = rememberLazyListState()
    val searchScroll = rememberLazyListState()
    var draftPicker by rememberSaveable { mutableStateOf(false) }
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(state.page != Page.Capture) { model.back() }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Accent, onPrimary = Color.White, background = Paper, surface = Paper,
        onBackground = Ink, onSurface = Ink, surfaceVariant = Color(0xFFEDE7DD),
        onSurfaceVariant = Color(0xFF766B60), outlineVariant = Color(0xFFDFD5C6),
    )) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                if (state.page == Page.Capture) {
                    ArtCrop(0, 0, 780, 148, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (state.page != Page.Records) {
                            TextButton(onClick = model::back, enabled = !state.busy) { Text("返回") }
                        }
                        Text(when (state.page) {
                            Page.Editor -> "记录这一刻"
                            Page.Search -> "搜索全部记录"
                            else -> "记录"
                        }, Modifier.weight(1f), fontSize = 22.sp, fontFamily = FontFamily.Serif)
                        if (state.page == Page.Editor) {
                            Button(onClick = model::complete, enabled = !state.busy && state.editor?.hasContent == true && !state.recording && state.pendingAttachment == null && !state.importFailed && state.editor.needsReload == false) {
                                Text("完成")
                            }
                        }
                        if (state.page == Page.Detail && FolioViewModel.canEdit(state.detail)) {
                            TextButton(onClick = model::editRecord, enabled = !state.busy) { Text("编辑") }
                        }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error ->
                    ErrorMessage(error, if (state.page == Page.Detail) model::retryDetail
                        else if (state.page == Page.Records || state.page == Page.Search) model::refresh
                        else if (state.editor?.needsReload == true) model::reload else null)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.page) {
                        Page.Capture -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(21.dp)) {
                            ArtCrop(42, 184, 696, 656, Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClick = model::newText)
                                .semantics { contentDescription = "写一句" }.testTag("write"))
                            Spacer(Modifier.height(26.dp))
                            Row(Modifier.fillMaxWidth()) {
                                listOf("camera" to "拍照", "photo" to "选图", "audio" to "录音", "checklist" to "清单").forEachIndexed { index, (kind, label) ->
                                    ArtCrop(42 + index * 174, 892, 174, 232, Modifier.weight(1f)
                                        .clickable(role = Role.Button) { if (kind == "checklist") model.newChecklist() else launchMedia(kind, null) }
                                        .semantics { contentDescription = label }.testTag("quick-$kind"))
                                }
                            }
                            state.savedId?.let { id ->
                                TextButton(onClick = { model.openRecord(id) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                                    Text("已保存 · 查看")
                                }
                            }
                        }
                        Page.Editor -> MixedEditorScreen(model, launchMedia) { discardDialog = true }
                        Page.Records -> Column {
                            TextButton(onClick = { model.home(Page.Search) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text("搜索全部记录")
                            }
                            if (state.drafts.isNotEmpty()) {
                                FilledTonalButton(onClick = {
                                    if (state.drafts.size == 1) model.resume(state.drafts.single()) else draftPicker = true
                                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    Text(if (state.drafts.size == 1) "继续草稿" else "继续草稿 · ${state.drafts.size} 份")
                                }
                            }
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(selected = !state.recent, onClick = { model.selectRecent(false) }, label = { Text("事件时间") })
                                FilterChip(selected = state.recent, onClick = { model.selectRecent(true) }, label = { Text("最近记录") })
                            }
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!state.loading && state.error == null && state.records.isEmpty()) EmptyMessage("还没有记录\n从「写一句」开始，完成后会出现在这里。")
                            LazyColumn(state = recordsScroll, contentPadding = PaddingValues(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("records-list")) {
                                if (state.recent) items(state.records, key = { it.id.value }) { RecordPreview(it, model) }
                                else {
                                    state.timeline.years.forEach { year ->
                                        item("year-${year.year}") { Text("${year.year} 年", style = MaterialTheme.typography.titleLarge) }
                                        items(year.records, key = { it.id.value }) { RecordPreview(it, model) }
                                    }
                                    if (state.timeline.undated.isNotEmpty()) {
                                        item("undated") { Text("时间未定", style = MaterialTheme.typography.titleMedium) }
                                        items(state.timeline.undated, key = { it.id.value }) { RecordPreview(it, model) }
                                    }
                                }
                            }
                        }
                        Page.Search -> Column(Modifier.padding(horizontal = 20.dp)) {
                            OutlinedTextField(value = state.query, onValueChange = model::query, singleLine = true,
                                label = { Text("搜索记录") }, placeholder = { Text("输入记得的一句话") },
                                modifier = Modifier.fillMaxWidth().testTag("search"),
                                trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { model.query("") }) { Text("清空") } })
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!state.loading && state.error == null) {
                                Text("${state.results.size} 条记录", Modifier.padding(vertical = 12.dp), fontSize = 12.sp)
                                if (state.results.isEmpty()) EmptyMessage("没有找到匹配的记录\n试试其他词，或清空搜索词。")
                            }
                            LazyColumn(state = searchScroll, contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("search-results")) {
                                if (state.error == null) items(state.results, key = { it.id.value }) { RecordPreview(it, model) }
                            }
                        }
                        Page.Detail -> state.detail?.let { content ->
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                state.drafts.firstOrNull { it.recordId == state.detailId }?.let { draft ->
                                    Text("另有未完成修改，当前显示上次完成的内容。", fontSize = 13.sp)
                                    TextButton(onClick = { model.resume(draft) }, enabled = !state.busy) { Text("继续未完成修改") }
                                }
                                Text(content.times.eventTime?.date?.toString() ?: "事件时间未填写", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                content.items.forEach { item -> when (item) {
                                    is ContentItem.Text -> Text(item.text, fontSize = 18.sp, lineHeight = 32.sp)
                                    is ContentItem.Photo -> { Photo(item.photoId); if (item.caption.isNotEmpty()) Text(item.caption) }
                                    is ContentItem.Audio -> { AudioControl(item.audioId, item.durationMillis, model); if (item.caption.isNotEmpty()) Text(item.caption) }
                                    is ContentItem.Checklist -> item.entries.forEach { Text("${if (it.checked) "☑" else "☐"} ${it.text}") }
                                } }
                                if (content.tags.isNotEmpty()) Text(content.tags.joinToString(" · ") { it.name }, color = Accent)
                                HorizontalDivider()
                                Text("记录于 ${formatDate(content.times.firstRecordedAt)}", fontSize = 12.sp)
                                if (content.times.modifiedAt != content.times.firstRecordedAt) Text("修改于 ${formatDate(content.times.modifiedAt)}", fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (state.page == Page.Capture || state.page == Page.Records) {
                    HorizontalDivider(Modifier.padding(horizontal = 22.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        listOf(Page.Capture to "随手记", Page.Records to "记录").forEach { (page, label) ->
                            Tab(selected = state.page == page, onClick = { model.home(page) },
                                modifier = Modifier.weight(1f).testTag("nav-$page"), text = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(label, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                                        Spacer(Modifier.height(6.dp))
                                        Box(Modifier.size(22.dp, 2.dp).background(if (state.page == page) Accent else Color.Transparent))
                                    }
                                })
                        }
                    }
                }
            }
        }
        if (draftPicker) AlertDialog(onDismissRequest = { draftPicker = false }, title = { Text("继续草稿") },
            text = { LazyColumn {
                item { Text("最近编辑的排在前面", fontSize = 12.sp) }
                items(state.drafts, key = { it.id.value }) { draft ->
                    TextButton(onClick = { draftPicker = false; model.resume(draft) }, modifier = Modifier.fillMaxWidth()) {
                        Text(draft.excerpt.ifBlank { if (draft.firstPhotoId != null) "照片草稿" else if (draft.totalAudioDurationMillis != null) "录音草稿" else "空白草稿" }, maxLines = 3)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { draftPicker = false }) { Text("关闭") } })
        if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("放弃这次修改？") },
            text = { Text(if (state.editor?.recordId != null) "草稿将移除，已完成的原记录会保留。" else "这份未完成的草稿将被移除。") },
            confirmButton = { TextButton(onClick = { discardDialog = false; model.discard() }) { Text("确认放弃") } },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("继续编辑") } })
    }
}

@Composable
private fun ArtCrop(x: Int, y: Int, width: Int, height: Int, modifier: Modifier) {
    val art = ImageBitmap.imageResource(R.drawable.home_art)
    Canvas(modifier.aspectRatio(width.toFloat() / height)) {
        drawImage(art, srcOffset = IntOffset(x, y), srcSize = IntSize(width, height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

@Composable
internal fun ErrorMessage(message: String, retry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        if (retry != null) TextButton(onClick = retry) { Text("重试") }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 28.dp), lineHeight = 26.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RecordPreview(record: RecordCard, model: FolioViewModel) {
    Column(Modifier.fillMaxWidth().clickable { model.openRecord(record.id) }.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOfNotNull(record.eventTime?.date?.toString(), if (record.marks.isPinned) "置顶" else null,
            if (record.marks.isStarred) "★" else null).joinToString(" · "), fontSize = 12.sp, color = Accent)
        record.preview.items.forEach { item -> when (item) {
            is PreviewContentItem.Text -> Text(item.text, maxLines = 4, lineHeight = 26.sp)
            is PreviewContentItem.Photo -> { Photo(item.photoId); if (item.caption.isNotEmpty()) Text(item.caption) }
            is PreviewContentItem.Audio -> { AudioControl(item.audioId, item.durationMillis, model); if (item.caption.isNotEmpty()) Text(item.caption) }
            is PreviewContentItem.Checklist -> item.entries.forEach { Text("${if (it.checked) "☑" else "☐"} ${it.text}") }
        } }
        if (record.preview.remainingItemCount > 0) Text("还有 ${record.preview.remainingItemCount} 项内容", fontSize = 12.sp)
        HorizontalDivider()
    }
}

@Composable
internal fun Photo(id: PhotoId) {
    val context = LocalContext.current
    var failed by remember(id) { mutableStateOf(false) }
    var expanded by remember(id) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, id) {
        try {
            value = withContext(Dispatchers.IO) {
                val originals = LocalPhotoOriginals.get(context)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                originals.openOriginal(id).use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1024) }
                originals.openOriginal(id).use { requireNotNull(BitmapFactory.decodeStream(it, null, options)).asImageBitmap() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    bitmap?.let { Image(it, "记录中的照片，点击放大", Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable { expanded = true }) }
        ?: Text(if (failed) "照片读取失败，请重新打开记录。" else "正在读取照片…")
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                bitmap?.let { Image(it, "照片", Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }) }
                TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) { Text("关闭照片", color = Color.White) }
            }
        }
    }
}

private fun formatDate(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
