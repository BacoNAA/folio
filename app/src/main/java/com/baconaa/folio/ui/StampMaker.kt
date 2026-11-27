package com.baconaa.folio.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.baconaa.folio.media.*
import com.baconaa.folio.records.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TagStampIcon(tag: Tag, model: FolioViewModel, modifier: Modifier = Modifier.size(32.dp)) {
    if (tag.stampRevision == null) return
    var retry by remember { mutableIntStateOf(0) }
    var image by remember(tag, retry) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(tag, retry) { mutableStateOf(false) }
    LaunchedEffect(tag, retry) {
        try { image = requireNotNull(model.stampImage(tag)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
    }
    image?.let { Image(it.asImageBitmap(), "图章 · ${tag.name}", modifier) }
    if (failed) TextButton(onClick = { retry++ }) { Text("重读图章") }
}

private enum class StampSource { Saved, Icon, Photo, Document }

@Composable
internal fun StampMaker(model: FolioViewModel, initial: Tag?, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var source by rememberSaveable { mutableStateOf(if (initial?.stampRevision == null) StampSource.Icon else StampSource.Saved) }
    var key by rememberSaveable { mutableStateOf(StampArtwork.symbols.first()) }
    var crop by rememberSaveable { mutableStateOf(false) }
    var x by rememberSaveable { mutableFloatStateOf(.5f) }
    var y by rememberSaveable { mutableFloatStateOf(.5f) }
    var scale by rememberSaveable { mutableFloatStateOf(.6f) }
    var failure by remember(source, key) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var image by remember(source, key, attempt) { mutableStateOf<Bitmap?>(null) }
    var encoding by remember { mutableStateOf(false) }
    val enabled = !model.state.busy && !encoding
    val region = if (crop) StampCrop(x, y, scale) else null
    fun choose(kind: StampSource, id: String) {
        source = kind; key = id; crop = kind == StampSource.Photo
        x = .5f; y = .5f; scale = .6f
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) choose(StampSource.Document, uri.toString())
    }
    LaunchedEffect(source, key, attempt) {
        failure = null
        try {
            image = withContext(Dispatchers.IO) {
                when (source) {
                    StampSource.Saved -> requireNotNull(model.stampImage(requireNotNull(initial)))
                    StampSource.Icon -> StampArtwork.decode(StampArtwork.icon(key))
                    StampSource.Photo -> StampArtwork.preview(LocalPhotoOriginals.get(context).stampSource(PhotoId(UUID.fromString(key))))
                    StampSource.Document -> StampArtwork.preview(ImageDecoder.createSource(context.contentResolver, Uri.parse(key)))
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failure = "图片读取失败，可重试或重新选择。" }
    }
    AlertDialog(onDismissRequest = { if (enabled) close() }, title = { Text(if (initial == null) "制作图章" else "编辑图章") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (initial != null && model.state.sharedTags.any { it.id == initial.id }) Text("这是共享标签。保存后，所有引用它的记录都会使用新名称和图案。")
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("图章名称") },
                modifier = Modifier.fillMaxWidth().testTag("stamp-name"), enabled = enabled)
            FlowRow {
                StampArtwork.symbols.forEach { symbol ->
                    FilterChip(source == StampSource.Icon && key == symbol, { choose(StampSource.Icon, symbol) },
                        label = { Text(symbol) }, enabled = enabled)
                }
            }
            val photos = model.state.editor?.items?.filterIsInstance<ContentItem.Photo>().orEmpty()
            if (photos.isNotEmpty()) {
                Text("本记录的照片", style = MaterialTheme.typography.labelLarge)
                LazyRow {
                    items(photos, key = { it.id.value }) { item ->
                        TextButton(onClick = { choose(StampSource.Photo, item.photoId.value.toString()) }, enabled = enabled,
                            modifier = Modifier.testTag("stamp-photo-${item.id.value}")) {
                            Photo(item.photoId, thumbnail = true, thumbnailDescription = item.caption.ifBlank { "选择照片" })
                        }
                    }
                }
            }
            TextButton(onClick = { open.launch(arrayOf("image/*")) }, enabled = enabled) { Text("从文件选择照片或小画") }
            failure?.let { ErrorMessage(it) { attempt++ } }
            model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val bmp = image
            if (bmp != null) {
                StampCropPreview(bmp, region)
                if (source == StampSource.Photo || source == StampSource.Document) {
                    FilterChip(selected = crop, onClick = { crop = !crop }, label = { Text(if (crop) "裁取局部" else "保留整幅") }, enabled = enabled)
                }
                if (crop) {
                    Text("选区大小"); Slider(scale, { scale = it }, valueRange = .05f..1f, enabled = enabled)
                    Text("水平位置"); Slider(x, { x = it }, enabled = enabled, modifier = Modifier.testTag("stamp-crop-x"))
                    Text("垂直位置"); Slider(y, { y = it }, enabled = enabled)
                }
            } else if (failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = {
        val bmp = image ?: return@TextButton
        encoding = true
        scope.launch {
            try {
                val png = withContext(Dispatchers.Default) { StampArtwork.render(bmp, region) }
                model.saveStamp(initial?.id, name, png, close)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failure = "图章生成失败，请重新选择。" }
            finally { encoding = false }
        }
    }, enabled = enabled && image != null && name.isNotBlank() && model.state.editor?.needsReload != true) { Text("保存图章") } },
        dismissButton = { TextButton(onClick = close, enabled = enabled) { Text("取消") } })
}

@Composable
private fun StampCropPreview(bitmap: Bitmap, crop: StampCrop?) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, 160.dp * ratio)
        Canvas(Modifier.size(width, width / ratio)) {
            val dest = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            drawImage(bitmap.asImageBitmap(), dstSize = dest)
            crop?.bounds(bitmap.width, bitmap.height)?.let { rect ->
                val sx = size.width / bitmap.width
                val sy = size.height / bitmap.height
                val offset = Offset(rect.left * sx, rect.top * sy)
                val box = Size(rect.width() * sx, rect.height() * sy)
                drawRect(Color.Black, offset, box, style = Stroke(4.dp.toPx()))
                drawRect(Color.White, offset, box, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
