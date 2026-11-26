package com.baconaa.folio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baconaa.folio.R
import com.baconaa.folio.records.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Paper = Color(0xFFF7F4EE)
private val Ink = Color(0xFF423A32)
private val Accent = Color(0xFF956B50)

@Composable
fun FolioApp(model: FolioViewModel, launchMedia: (String, ItemId?) -> Unit = { _, _ -> },
    chooseDocument: (ImportType, ItemId?) -> Unit = { _, _ -> }) {
    val state = model.state
    val recordsScroll = rememberLazyListState()
    val searchScroll = rememberLazyListState()
    val placesScroll = rememberLazyListState()
    val groupsScroll = rememberLazyListState()
    val membersScroll = key(state.groupId) { rememberLazyListState() }
    val guideScroll = key(state.groupId) { rememberLazyListState() }
    val pickerScroll = key(state.groupSelection?.creationId) { rememberLazyListState() }
    var draftPicker by rememberSaveable { mutableStateOf(false) }
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var eraseDialog by rememberSaveable { mutableStateOf(false) }
    var discardGuide by rememberSaveable { mutableStateOf(false) }
    var deletedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var deletedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var restoredScrollVersion by rememberSaveable { mutableIntStateOf(0) }
    // 快速重试可能在两帧之间完成，应在列表从失败变为可读时恢复位置。
    val listReady = !state.loading && state.groupReadError == null && state.error == null
    LaunchedEffect(state.scrollRestoreVersion, state.page, listReady) {
        if (listReady && state.scrollRestoreVersion > restoredScrollVersion) {
            val scroll = when (state.page) {
                Page.GroupDetail -> membersScroll.takeIf { state.groupContent != null }
                Page.GroupGuide -> guideScroll.takeIf { state.groupGuide != null }
                Page.Search -> searchScroll
                Page.Records -> recordsScroll
                else -> null
            }
            scroll?.scrollToItem(deletedScrollIndex, deletedScrollOffset)
            restoredScrollVersion = state.scrollRestoreVersion
        }
    }
    val goBack = {
        if (!state.busy && state.page == Page.GroupGuide && state.guideEdit != null && state.guideEdit.layout != state.groupGuide?.layout)
            discardGuide = true
        else model.back()
    }
    BackHandler(state.page != Page.Capture, onBack = goBack)

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Accent, onPrimary = Color.White, background = Paper, surface = Paper,
        secondary = Accent, secondaryContainer = Color(0xFFEADFD0), onSecondaryContainer = Ink,
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
                            TextButton(onClick = goBack, enabled = !state.busy) { Text("返回") }
                        }
                        Text(when (state.page) {
                            Page.Editor -> if (state.editor?.splitSource != null) "拆分草稿" else "记录这一刻"
                            Page.Search -> if (state.placeBook != null) "地点小册" else "搜索全部记录"
                            Page.Places -> "按地点"
                            Page.Groups -> "记录组"
                            Page.GroupDetail -> state.groupContent?.group?.name ?: "记录组"
                            Page.GroupGuide -> if (state.guideEdit != null) "编辑导览" else "专题导览"
                            Page.GroupPicker -> "选择记录"
                            Page.GroupDestination -> "收录到记录组"
                            Page.Trash, Page.TrashDetail -> "回收站"
                            else -> "记录"
                        }, Modifier.weight(1f), fontSize = 22.sp, fontFamily = FontFamily.Serif,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (state.page == Page.Editor) {
                            Button(onClick = model::complete, enabled = !state.busy && state.editor?.hasContent == true && !state.recording && state.pendingAttachment == null && !state.importFailed && state.editor.needsReload == false) {
                                Text("完成")
                            }
                        }
                        if (state.page == Page.Detail && FolioViewModel.canEdit(state.detail)) {
                            TextButton(onClick = model::editRecord, enabled = !state.busy) { Text("编辑") }
                        }
                        if (state.page == Page.GroupGuide && state.guideEdit != null) {
                            Button(onClick = model::saveGuide, enabled = !state.busy && state.guideEdit.conflicting == null) { Text("保存导览") }
                        }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.takeIf { state.transfer == null && state.templateLibrary == null }?.let { error ->
                    val retry = when {
                        state.draftReadRetry != null -> model::retryDraftRead
                        state.page == Page.Detail -> model::retryDetail
                        state.page == Page.TrashDetail -> model::retryTrashDetail
                        state.page == Page.GroupGuide && state.guideEdit != null -> model::saveGuide
                        state.page == Page.Records || state.page == Page.Search || state.page == Page.Trash -> model::refresh
                        state.editor?.needsReload == true -> model::reload
                        else -> null
                    }
                    ErrorMessage(error, retry)
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
                            TextButton(onClick = model::openTemplates, enabled = !state.busy) { Text("记录模板") }
                            state.savedId?.let { id ->
                                TextButton(onClick = { model.openRecord(id) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                                    Text("已保存 · 查看")
                                }
                            }
                        }
                        Page.Editor -> MixedEditorScreen(model, launchMedia, chooseDocument) { discardDialog = true }
                        Page.Records -> Column {
                            FlowRow {
                                TextButton(onClick = model::openSearch) { Text("搜索全部记录") }
                                TextButton(onClick = model::openStarred) { Text("星标") }
                                TextButton(onClick = { model.home(Page.Trash) }) { Text("回收站") }
                                TextButton(onClick = { model.home(Page.Groups) }) { Text("记录组") }
                            }
                            if (state.drafts.isNotEmpty()) {
                                FilledTonalButton(onClick = {
                                    if (state.drafts.size == 1) model.resume(state.drafts.single()) else draftPicker = true
                                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    Text(if (state.drafts.size == 1) "继续草稿" else "继续草稿 · ${state.drafts.size} 份")
                                }
                            }
                            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(selected = !state.recent, onClick = { model.selectRecent(false) }, label = { Text("事件时间") })
                                FilterChip(selected = state.recent, onClick = { model.selectRecent(true) }, label = { Text("最近记录") })
                                TextButton(onClick = { model.home(Page.Places) }) { Text("按地点") }
                                TextButton(onClick = model::pickGroupMembers) { Text("多选成组") }
                            }
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!state.loading && state.error == null && state.records.isEmpty()) EmptyMessage("还没有记录\n从「写一句」开始，完成后会出现在这里。")
                            LazyColumn(state = recordsScroll, contentPadding = PaddingValues(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("records-list")) {
                                if (state.recent) items(state.records, key = { it.id.value }) { RecordPreview(it, model) }
                                else eventTimelineItems(state.timeline, model)
                            }
                        }
                        Page.Search -> Column(Modifier.padding(horizontal = 20.dp)) {
                            PlaceBookHeader(model)
                            state.linkingPlace?.let { Text("选择要关联到「${it.info.name}」的记录，进入编辑后点完成才生效。", fontSize = 12.sp) }
                            OutlinedTextField(value = state.query, onValueChange = model::query, singleLine = true,
                                label = { Text(state.placeBook?.let { "在${it.info.name}中查找" } ?: "搜索记录") }, placeholder = { Text("输入记得的一句话") },
                                modifier = Modifier.fillMaxWidth().testTag("search"),
                                trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { model.query("") }) { Text("清空") } })
                            SearchFilters(model)
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!state.loading && state.error == null) {
                                SearchResultSummary(state.results.size + state.possibleResults.size, state.filterPlace?.info?.name)
                                LazyColumn(state = searchScroll, contentPadding = PaddingValues(vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("search-results")) {
                                    if (state.undatedMatchCount > 0) item {
                                        TextButton(onClick = { model.filterEventDate(EventDateFilter.Undated) }) {
                                            Text("查看时间未定记录（${state.undatedMatchCount} 条）")
                                        }
                                    }
                                    if (state.results.isEmpty() && state.possibleResults.isEmpty()) item {
                                        Column {
                                            EmptyMessage("没有同时符合这些条件的记录\n可移除筛选条件，或修改搜索词。")
                                            if (state.eventDateFilter != null) TextButton(onClick = { model.filterEventDate(null) }) {
                                                Text("移除时间限制")
                                            }
                                            if (state.mediaTypes.isNotEmpty()) TextButton(onClick = { model.filterMedia(emptySet()) }) {
                                                Text("移除内容类型限制")
                                            }
                                        }
                                    }
                                    val timeFilter = state.eventDateFilter
                                    if (timeFilter is EventDateFilter.Range) {
                                        item { Text("明确符合 ${state.results.size} 条") }
                                        items(state.results, key = { it.id.value }) { RecordPreview(it, model) }
                                        item { Text("可能符合 ${state.possibleResults.size} 条") }
                                        items(state.possibleResults, key = { it.id.value }) { record ->
                                            Column {
                                                val dates = requireNotNull(record.eventTime?.dateRange)
                                                Text("记录范围 ${dates.start} 至 ${dates.endInclusive}，与所查时段（${timeFilter.inputLabel}）部分重合。", fontSize = 12.sp)
                                                RecordPreview(record, model)
                                            }
                                        }
                                    } else if (state.placeBook == null || state.placeRecent) items(state.results, key = { it.id.value }) { RecordPreview(it, model) }
                                    else eventTimelineItems(eventTimeline(state.results), model)
                                }
                            }
                        }
                        Page.Detail -> state.detail?.let { content ->
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(content.marks.isStarred, { model.setStarred(!content.marks.isStarred) },
                                        enabled = !state.busy, label = { Text(if (content.marks.isStarred) "已加星" else "加星") })
                                    Spacer(Modifier.width(12.dp))
                                    FilterChip(content.marks.isPinned, { model.setPinned(!content.marks.isPinned) },
                                        enabled = !state.busy, label = { Text(if (content.marks.isPinned) "已置顶" else "置顶") })
                                    TextButton(onClick = { deleteDialog = true }, enabled = !state.busy) { Text("删除") }
                                }
                                val pending = state.drafts.firstOrNull { it.recordId == state.detailId || it.splitSource == state.detailId }
                                pending?.let { draft ->
                                    if (draft.splitSource != null) Text("已有拆分草稿。完成或放弃后可再次编辑、拆分。", fontSize = 13.sp)
                                    else Text("另有未完成修改，当前显示上次完成的内容。", fontSize = 13.sp)
                                    TextButton(onClick = { model.resume(draft) }, enabled = !state.busy) {
                                        Text(if (draft.splitSource != null) "继续拆分草稿" else "继续未完成修改")
                                    }
                                }
                                RecordContents(content, model)
                                if (pending?.recordId != null) Text("完成或放弃草稿后可拆分。", fontSize = 13.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { model.startTransfer(split = false) }, enabled = !state.busy) { Text("复制为新记录") }
                                    OutlinedButton(onClick = { model.startTransfer(split = true) }, enabled = !state.busy && pending == null) { Text("拆分记录") }
                                    OutlinedButton(onClick = { model.editTemplate(extract = true) }, enabled = !state.busy) { Text("提取为模板") }
                                }
                            }
                        }
                        Page.Places -> PlacesScreen(model, placesScroll)
                        Page.Groups, Page.GroupDetail, Page.GroupPicker, Page.GroupDestination, Page.GroupGuide ->
                            RecordGroupScreens(model, groupsScroll, membersScroll, pickerScroll, guideScroll)
                        Page.Trash -> TrashScreen(model)
                        Page.TrashDetail -> state.trashDetail?.let { record ->
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Row {
                                    Button(onClick = { model.restoreRecord(record.id) }, enabled = !state.busy) { Text("恢复记录") }
                                    TextButton(onClick = { eraseDialog = true }, enabled = !state.busy) { Text("彻底删除") }
                                }
                                Text(if (record.hasDraft) "有未完成修改；这里只读展示上次完成的内容，恢复后可继续草稿。" else "这里只读展示上次完成的内容。", fontSize = 13.sp)
                                RecordContents(record.content, model)
                            }
                        }
                    }
                }
                if (state.cleanupPending) ErrorMessage("记录已删除，部分媒体文件待清理。", model::retryCleanup)
                if (state.notice != null && state.page != Page.Editor && state.guideEdit == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.notice, Modifier.weight(1f), fontSize = 12.sp)
                    if (state.deleted != null) TextButton(onClick = model::undoDelete, enabled = !state.busy) { Text("撤销删除") }
                    state.restoredId?.let { id -> TextButton(onClick = { model.openRecord(id) }, enabled = !state.busy) { Text("查看恢复记录") } }
                    TextButton(onClick = model::dismissNotice) { Text("关闭提示") }
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
        state.templateLibrary?.let { TemplateLibraryDialog(it, model) }
        state.transfer?.let { transfer ->
            state.detail?.let { RecordTransferDialog(it, transfer, model) }
        }
        if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("移入回收站？") },
            text = { Text("正式内容和这条记录的未完成修改会一起保留，可在回收站恢复。") },
            confirmButton = { TextButton(onClick = {
                val scroll = when (state.detailOrigin) {
                    Page.GroupDetail -> membersScroll
                    Page.GroupGuide -> guideScroll
                    Page.Search -> searchScroll
                    else -> recordsScroll
                }
                deletedScrollIndex = scroll.firstVisibleItemIndex; deletedScrollOffset = scroll.firstVisibleItemScrollOffset
                deleteDialog = false; model.deleteRecord()
            }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
        if (eraseDialog) AlertDialog(onDismissRequest = { eraseDialog = false }, title = { Text("彻底删除这条记录？") },
            text = { Text("正式内容及其未完成草稿会一并删除，无法从回收站找回。") },
            confirmButton = { TextButton(onClick = { eraseDialog = false; state.trashId?.let(model::deletePermanently) }) { Text("确认彻底删除") } },
            dismissButton = { TextButton(onClick = { eraseDialog = false }) { Text("取消") } })
        if (discardGuide) AlertDialog(onDismissRequest = { discardGuide = false }, title = { Text("放弃未保存的导览修改？") },
            confirmButton = { TextButton(onClick = { discardGuide = false; model.back() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { discardGuide = false }) { Text("继续编辑") } })
        if (draftPicker) AlertDialog(onDismissRequest = { draftPicker = false }, title = { Text("继续草稿") },
            text = { LazyColumn {
                item { Text("最近编辑的排在前面", fontSize = 12.sp) }
                items(state.drafts, key = { it.id.value }) { draft ->
                    DraftPickerRow(draft, onClick = { draftPicker = false; model.resume(draft) })
                }
            } }, confirmButton = { TextButton(onClick = { draftPicker = false }) { Text("关闭") } })
        if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("放弃这次修改？") },
            text = { Text(if (state.editor?.recordId != null) "草稿将移除，已完成的原记录会保留。" else "这份未完成的草稿将被移除。") },
            confirmButton = { TextButton(onClick = { discardDialog = false; model.discard() }) { Text("确认放弃") } },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("继续编辑") } })
    }
}

@Composable
private fun DraftPickerRow(draft: DraftPreview, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("draft-${draft.id.value}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (draft.excerpt.isBlank()) draft.firstPhotoId?.let { Photo(it, thumbnail = true) }
            Column {
                if (draft.splitSource != null) Text("拆分草稿", style = MaterialTheme.typography.labelMedium)
                draft.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 2) }
                val description = when {
                    draft.excerpt.isNotBlank() -> draft.excerpt
                    draft.totalAudioDurationMillis != null -> "录音 · ${formatAudioDuration(draft.totalAudioDurationMillis)}"
                    draft.title == null && draft.firstPhotoId == null -> "空白草稿"
                    else -> null
                }
                description?.let { Text(it, maxLines = 3) }
            }
        }
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
private fun SearchResultSummary(count: Int, placeName: String?) {
    var showScope by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("$count 条记录", fontSize = 12.sp)
        TextButton(onClick = { showScope = true }) { Text("搜索范围") }
    }
    if (showScope) AlertDialog(onDismissRequest = { showScope = false },
        title = { Text("可搜索的内容") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(placeName?.let { "只查关联到「$it」的正式记录。" } ?: "查找全部正式记录。")
            Text("可搜标题、正文、清单、标签、地点名、人物和物件的名称及别名，也可搜自己写的时间描述。")
            Text("PDF 附件和视频只匹配文件名。照片和录音可搜你填写的说明。照片中的文字和录音中的语音不参与匹配。")
            Text("网址可按地址、保存的标题和备注查找。")
        } },
        confirmButton = { TextButton(onClick = { showScope = false }) { Text("关闭") } })
}

private fun LazyListScope.eventTimelineItems(timeline: EventTimeline, model: FolioViewModel) {
    timeline.years.forEach { year ->
        item("year-${year.year}") { Text("${year.year} 年", style = MaterialTheme.typography.titleLarge) }
        items(year.records, key = { it.id.value }) { RecordPreview(it, model) }
        if (year.approximate.isNotEmpty()) {
            item("approximate-${year.year}") { Text("大概时间", style = MaterialTheme.typography.titleMedium) }
            items(year.approximate, key = { it.id.value }) { RecordPreview(it, model) }
        }
    }
    if (timeline.crossYear.isNotEmpty()) {
        item("cross-year") { Text("跨年经历", style = MaterialTheme.typography.titleMedium) }
        items(timeline.crossYear, key = { it.id.value }) { RecordPreview(it, model) }
    }
    if (timeline.undated.isNotEmpty()) {
        item("undated") { Text("时间未定", style = MaterialTheme.typography.titleMedium) }
        items(timeline.undated, key = { it.id.value }) { RecordPreview(it, model) }
    }
}

@Composable
internal fun RecordPreview(record: RecordCard, model: FolioViewModel, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { model.openRecord(record.id) }.heightIn(min = 48.dp).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RecordCardSummary(record, model)
            if (record.tags.isNotEmpty()) Row {
                record.tags.take(2).forEach { tag -> TextButton(onClick = { model.openTag(tag) }) { Text(tag.name) } }
                if (record.tags.size > 2) Text("另有 ${record.tags.size - 2} 个标签", fontSize = 12.sp)
            }
            if (record.objects.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                record.objects.forEach { value -> TextButton(onClick = { model.openObject(value) }) {
                    Text("${value.kind.label}：${value.info.name}${value.info.note.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}")
                } }
            }
        }
        actions?.invoke()
        HorizontalDivider()
    }
}

@Composable
internal fun RecordCardSummary(record: RecordCard, model: FolioViewModel? = null) {
    val eventTime = record.eventTime
    val dateLabel = if (eventTime is EventTime.Exact) eventTime.date.toString() else eventTime?.label
    val metadata = listOfNotNull(dateLabel, record.place?.info?.name,
        "置顶".takeIf { record.marks.isPinned }, "★".takeIf { record.marks.isStarred })
    if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), fontSize = 12.sp, color = Accent)
    record.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    record.preview.items.forEach { item -> when (item) {
        is PreviewContentItem.Text -> Text(item.text, maxLines = 4, lineHeight = 26.sp)
        is PreviewContentItem.Pdf -> Text(item.displayName)
        is PreviewContentItem.Link -> Text(item.label, maxLines = 3)
        is PreviewContentItem.Video -> Text(videoLabel(item.displayName, item.durationMillis))
        is PreviewContentItem.Photo -> {
            Photo(item.photoId, thumbnail = model == null, thumbnailDescription = "记录照片缩略图")
            if (item.caption.isNotEmpty()) Text(item.caption)
        }
        is PreviewContentItem.Audio -> {
            if (model != null) AudioControl(item.audioId, item.durationMillis, model)
            else Text("录音 · ${formatAudioDuration(item.durationMillis)}")
            if (item.caption.isNotEmpty()) Text(item.caption)
        }
        is PreviewContentItem.Checklist -> {
            Text("清单 · 已完成 ${item.checkedEntryCount}/${item.totalEntryCount} 项", fontSize = 12.sp)
            item.entries.forEach { Text("${if (it.checked) "☑" else "☐"} ${it.text}") }
        }
    } }
    val preview = record.preview
    if (preview.remainingItemCount > 0) {
        val remaining = buildList {
            add("还有 ${preview.remainingItemCount} 项内容")
            if (preview.remainingPhotoCount > 0) add("${preview.remainingPhotoCount} 张照片")
            if (preview.remainingAudioCount > 0) add("${preview.remainingAudioCount} 段录音")
        }
        Text(remaining.joinToString(" · "), fontSize = 12.sp)
    }
}

internal fun formatDate(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
