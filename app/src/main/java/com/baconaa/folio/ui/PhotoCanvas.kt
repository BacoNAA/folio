package com.baconaa.folio.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.baconaa.folio.records.PhotoPoint
import com.baconaa.folio.records.PhotoStroke

internal class PhotoBitmap(val pixels: Bitmap, orientation: Int) {
    val rawBounds = RectF(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat())
    val upright = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setScale(1f, -1f); postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setScale(-1f, 1f); postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    val uprightBounds = RectF().also { upright.mapRect(it, rawBounds); upright.postTranslate(-it.left, -it.top) }
    val aspectRatio = uprightBounds.width() / uprightBounds.height()
}

internal class PhotoViewport {
    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun adjust(factor: Float, pan: Offset = Offset.Zero, focusFromCenter: Offset = Offset.Zero) {
        val next = (zoom * factor).coerceIn(1f, 5f)
        offset = if (next == 1f) Offset.Zero else focusFromCenter + (offset - focusFromCenter) * (next / zoom) + pan
        zoom = next
    }

    fun reset() { zoom = 1f; offset = Offset.Zero }
}

// 同一矩阵绘制原图和笔画，触点经其逆矩阵回到原图像素。画布黑边不属于照片。
private class PhotoPlacement(val photo: PhotoBitmap, size: IntSize, crop: Boolean, zoom: Float, pan: Offset) {
    val matrix = Matrix(photo.upright)
    private val inverse = Matrix()
    init {
        val width = photo.uprightBounds.width()
        val height = photo.uprightBounds.height()
        val fit = if (crop) maxOf(size.width / width, size.height / height) else minOf(size.width / width, size.height / height)
        val scale = fit * zoom
        matrix.postScale(scale, scale)
        matrix.postTranslate((size.width - width * scale) / 2 + pan.x, (size.height - height * scale) / 2 + pan.y)
        check(matrix.invert(inverse))
    }

    fun pointAt(position: Offset, clamp: Boolean = false): PhotoPoint? {
        val xy = floatArrayOf(position.x, position.y)
        inverse.mapPoints(xy)
        val x = xy[0] / photo.rawBounds.width()
        val y = xy[1] / photo.rawBounds.height()
        if (!clamp && (x !in 0f..1f || y !in 0f..1f)) return null
        return PhotoPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}

private class InkPath(first: PhotoPoint, private val bitmap: Bitmap) {
    private val x = first.x * bitmap.width
    private val y = first.y * bitmap.height
    private var line: Path? = null

    fun append(point: PhotoPoint) {
        val path = line ?: Path().apply { moveTo(x, y) }.also { line = it }
        path.lineTo(point.x * bitmap.width, point.y * bitmap.height)
    }

    fun draw(canvas: android.graphics.Canvas, paint: Paint) {
        val path = line
        if (path == null) canvas.drawPoint(x, y, paint) else canvas.drawPath(path, paint)
    }
}

@Composable
internal fun PhotoCanvas(photo: PhotoBitmap, strokes: List<PhotoStroke>, modifier: Modifier,
    crop: Boolean = false, view: PhotoViewport? = null, enabled: Boolean = true, onStroke: ((PhotoStroke) -> Unit)? = null) {
    var extent by remember { mutableStateOf(IntSize.Zero) }
    val placement = remember(photo, extent, crop, view?.zoom, view?.offset) {
        if (extent.width == 0 || extent.height == 0) null
        else PhotoPlacement(photo, extent, crop, view?.zoom ?: 1f, view?.offset ?: Offset.Zero)
    }
    val savedPaths = remember(photo, strokes) {
        strokes.map { stroke ->
            InkPath(stroke.points.first(), photo.pixels).apply { for (i in 1 until stroke.points.size) append(stroke.points[i]) }
        }
    }
    var active by remember(photo) { mutableStateOf<InkPath?>(null, neverEqualPolicy()) }
    val currentPlacement by rememberUpdatedState(placement)
    val finish by rememberUpdatedState(onStroke)
    val canDraw = onStroke != null
    val gestures = if (view == null || !enabled) Modifier else Modifier.pointerInput(photo, extent, canDraw, view) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val frame = currentPlacement ?: return@awaitEachGesture
            val first = if (canDraw) frame.pointAt(down.position) else null
            val points = mutableListOf<PhotoPoint>()
            val path = first?.let { points.add(it); InkPath(it, photo.pixels) }
            active = path
            down.consume()
            var navigating = !canDraw
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    if (event.changes.size > 1) {
                        navigating = true
                        active = null
                    }
                    val finger = event.changes.first()
                    if (navigating) {
                        val focus = event.calculateCentroid(useCurrent = false)
                        if (focus != Offset.Unspecified) view.adjust(event.calculateZoom(), event.calculatePan(),
                            focus - Offset(size.width / 2f, size.height / 2f))
                    } else if (path != null) {
                        val point = requireNotNull(frame.pointAt(finger.position, clamp = true))
                        if (point != points.last()) { points.add(point); path.append(point); active = path }
                        if (!finger.pressed) finish?.invoke(PhotoStroke(points))
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally { active = null }
        }
    }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val pen = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    } }
    Canvas(modifier.clipToBounds().onSizeChanged { extent = it }.then(gestures)) {
        val frame = placement ?: return@Canvas
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            canvas.save()
            canvas.concat(frame.matrix)
            canvas.drawBitmap(photo.pixels, null, photo.rawBounds, bitmapPaint)
            val width = minOf(photo.pixels.width, photo.pixels.height) * 0.012f
            fun drawInk(color: Int, thickness: Float) {
                pen.color = color
                pen.strokeWidth = thickness
                savedPaths.forEach { path -> path.draw(canvas, pen) }
                active?.draw(canvas, pen)
            }
            drawInk(android.graphics.Color.WHITE, width * 1.6f)
            drawInk(0xFFE44932.toInt(), width)
            canvas.restore()
        }
    }
}
