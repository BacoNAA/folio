package com.baconaa.folio.media

import android.graphics.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** x/y are positions within the available crop travel; the far edge remains selectable at every size. */
data class StampCrop(val x: Float = .5f, val y: Float = .5f, val scale: Float = .6f) {
    fun bounds(width: Int, height: Int): Rect {
        val edge = (min(width, height) * scale.coerceIn(.05f, 1f)).roundToInt().coerceAtLeast(1)
        val left = ((width - edge) * x.coerceIn(0f, 1f)).roundToInt()
        val top = ((height - edge) * y.coerceIn(0f, 1f)).roundToInt()
        return Rect(left, top, left + edge, top + edge)
    }
}

internal object StampArtwork {
    const val EDGE = 96
    const val MAX_BYTES = 64 * 1024
    val symbols = listOf("★", "♥", "☕", "✿", "☀", "♫")

    fun preview(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (w, h) = info.size.let { it.width to it.height }
        require(w > 0 && h > 0 && w.toLong() * h <= 160_000_000L) { "图片过大，请改选较小的图片。" }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val ratio = min(1.0, 768.0 / maxOf(w, h))
        decoder.setTargetSize((w * ratio).roundToInt().coerceAtLeast(1), (h * ratio).roundToInt().coerceAtLeast(1))
    }

    fun render(source: Bitmap, crop: StampCrop?): ByteArray {
        val area = crop?.bounds(source.width, source.height) ?: Rect(0, 0, source.width, source.height)
        val ratio = EDGE.toFloat() / maxOf(area.width(), area.height())
        val dx = (EDGE - area.width() * ratio) / 2
        val dy = (EDGE - area.height() * ratio) / 2
        return png { canvas -> canvas.drawBitmap(source, area, RectF(dx, dy, EDGE - dx, EDGE - dy), Paint(Paint.FILTER_BITMAP_FLAG)) }
    }

    fun icon(symbol: String): ByteArray {
        require(symbol in symbols)
        return png { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(122, 67, 41)
                textSize = 72f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(symbol, EDGE / 2f, EDGE / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    private inline fun png(draw: (Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray().also { check(it.size <= MAX_BYTES) }
            }
        } finally { bitmap.recycle() }
    }

    fun decode(png: ByteArray): Bitmap {
        require(png.size in 1..MAX_BYTES) { "图章数据大小无效。" }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png))) { decoder, info, _ ->
            require(info.mimeType == "image/png" && info.size.width == EDGE && info.size.height == EDGE) { "图章格式或尺寸无效。" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
