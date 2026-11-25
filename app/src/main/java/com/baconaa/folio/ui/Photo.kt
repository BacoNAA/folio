package com.baconaa.folio.ui

import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.media.PhotoId
import com.baconaa.folio.records.ContentItem
import com.baconaa.folio.records.PhotoStroke
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun Photo(id: PhotoId, thumbnail: Boolean = false, thumbnailDescription: String = "草稿照片缩略图",
    strokes: List<PhotoStroke> = emptyList()) = key(id, thumbnail) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LoadedPhoto(id, thumbnail) { photo ->
        if (thumbnail) {
            PhotoCanvas(photo, emptyList(), Modifier.size(64.dp).semantics { contentDescription = thumbnailDescription }, crop = true)
        } else {
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable { expanded = true }
                .semantics { contentDescription = "记录中的照片，点击放大" }, contentAlignment = Alignment.Center) {
                PhotoCanvas(photo, strokes, Modifier.aspectRatio(photo.aspectRatio))
            }
            if (expanded) {
                var original by rememberSaveable { mutableStateOf(false) }
                PhotoDialog(onClose = { expanded = false }, actions = {
                    TextButton(onClick = { expanded = false }) { Text("关闭照片") }
                    if (strokes.isNotEmpty()) TextButton(onClick = { original = !original }) {
                        Text(if (original) "查看标注" else "查看原图")
                    }
                }) { ZoomablePhoto(photo, if (original) emptyList() else strokes) }
            }
        }
    }
}

@Composable
internal fun PhotoMarkupDialog(item: ContentItem.Photo, model: FolioViewModel) {
    val busy = model.state.busy
    val error = model.state.error ?: model.state.saveError
    PhotoDialog(onClose = model::cancelPhotoMarkup, actions = {
        TextButton(onClick = model::cancelPhotoMarkup, enabled = !busy) { Text("取消") }
        TextButton(onClick = { model.changePhotoMarkup { it.dropLast(1) } }, enabled = !busy && item.strokes.isNotEmpty()) { Text("撤销一笔") }
        TextButton(onClick = { model.changePhotoMarkup { emptyList() } }, enabled = !busy && item.strokes.isNotEmpty()) { Text("清除标注") }
        Button(onClick = model::savePhotoMarkup, enabled = !busy) { Text(if (error == null) "保存标注" else "重试保存") }
    }) {
        if (error != null) Text(error, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
        LoadedPhoto(item.photoId) { photo ->
            ZoomablePhoto(photo, item.strokes, enabled = !busy, onStroke = { stroke -> model.changePhotoMarkup { it + stroke } })
        }
    }
}

@Composable
private fun PhotoDialog(onClose: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween, content = actions)
                content()
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(photo: PhotoBitmap, strokes: List<PhotoStroke>, enabled: Boolean = true, onStroke: ((PhotoStroke) -> Unit)? = null) {
    val view = remember(photo) { PhotoViewport() }
    Column(Modifier.fillMaxSize()) {
        PhotoCanvas(photo, strokes, Modifier.fillMaxWidth().weight(1f).background(Color.Black).semantics {
            contentDescription = if (onStroke == null) "照片" else "照片圈画画布"
            stateDescription = "缩放 ${(view.zoom * 100).roundToInt()}%"
        }, view = view, enabled = enabled, onStroke = onStroke)
        Row(Modifier.align(Alignment.CenterHorizontally).horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { view.adjust(1f / 1.5f) }, enabled = enabled && view.zoom > 1f) { Text("缩小") }
            TextButton(onClick = { view.adjust(1.5f) }, enabled = enabled && view.zoom < 5f) { Text("放大") }
            TextButton(onClick = view::reset, enabled = enabled && view.zoom > 1f) { Text("还原") }
        }
    }
}

@Composable
private fun LoadedPhoto(id: PhotoId, thumbnail: Boolean = false, content: @Composable (PhotoBitmap) -> Unit) = key(id, thumbnail) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val bitmap by produceState<PhotoBitmap?>(null, attempt) {
        try {
            value = withContext(Dispatchers.IO) {
                val originals = LocalPhotoOriginals.get(context)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                originals.openOriginal(id).use { BitmapFactory.decodeStream(it, null, bounds) }
                val target = if (thumbnail) 192 else 1024
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > target) inSampleSize *= 2
                }
                val orientation = originals.openOriginal(id).use {
                    try { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
                }
                val raw = originals.openOriginal(id).use { requireNotNull(BitmapFactory.decodeStream(it, null, options)) }
                PhotoBitmap(raw, orientation)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val image = bitmap
    when {
        image != null -> content(image)
        failed -> Column {
            Text("照片读取失败。")
            if (!thumbnail) TextButton(onClick = { failed = false; attempt++ }) { Text("重试读取照片") }
        }
        else -> Text("正在读取照片…")
    }
}
