package com.baconaa.folio.records

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.util.RandomAccess

/** 相对于原图像素网格的坐标，左上角为 (0, 0)，右下角为 (1, 1)，独立于显示方向和缩放。 */
data class PhotoPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) { "标注点必须位于照片内" } }
}

data class PhotoStroke(val points: List<PhotoPoint>) {
    init { require(points.isNotEmpty()) { "一笔标注至少需要一个点" } }
}

// Room v23 使用大端字节序：笔画数、各笔结束时的累计点数，随后是依次排列的 Float 坐标对。
internal object PhotoStrokeStorage {
    private const val POINT_BYTES = 2 * Float.SIZE_BYTES
    private const val MAX_BYTES = 2 * 1024 * 1024  // Android 10 的 CursorWindow 还需容纳同一行的其他字段。

    @TypeConverter
    fun encode(strokes: List<PhotoStroke>?): ByteArray? {
        if (strokes.isNullOrEmpty()) return null
        if (strokes is StoredStrokes) return strokes.bytes
        val headerBytes = (1L + strokes.size) * Int.SIZE_BYTES
        var size = headerBytes
        for (stroke in strokes) {
            require(stroke.points.isNotEmpty()) { "一笔标注至少需要一个点" }
            size += stroke.points.size.toLong() * POINT_BYTES
            require(size <= MAX_BYTES) { "这张照片的标注过大，请减少笔画后重试" }
        }

        val data = ByteBuffer.allocate(size.toInt())
        data.putInt(strokes.size)
        data.position(headerBytes.toInt())
        var end = 0
        for ((index, stroke) in strokes.withIndex()) {
            for (point in stroke.points) data.putFloat(point.x).putFloat(point.y)
            end += stroke.points.size
            data.putInt((index + 1) * Int.SIZE_BYTES, end)
        }
        return data.array()
    }

    @TypeConverter
    fun decode(bytes: ByteArray?): List<PhotoStroke>? = bytes?.let(::StoredStrokes)

    // 详情、草稿共用紧凑的只读笔画。按需取点，续写文字时也能直接回存原字节。
    private class StoredStrokes(val bytes: ByteArray) : AbstractList<PhotoStroke>(), RandomAccess {
        private val data = ByteBuffer.wrap(bytes)
        override val size: Int
        private val pointsOffset: Int

        init {
            check(bytes.size in Int.SIZE_BYTES..MAX_BYTES) { "照片标注长度无效" }
            size = data.getInt(0)
            check(size in 1..(bytes.size - Int.SIZE_BYTES) / (Int.SIZE_BYTES + POINT_BYTES)) { "照片标注笔数无效" }
            pointsOffset = (size + 1) * Int.SIZE_BYTES
            val available = bytes.size - pointsOffset
            check(available % POINT_BYTES == 0) { "照片标注的坐标不完整" }
            var start = 0
            for (stroke in indices) {
                val end = data.getInt((stroke + 1) * Int.SIZE_BYTES)
                check(end > start && end <= available / POINT_BYTES) { "照片标注笔画无效" }
                start = end
            }
            check(start == available / POINT_BYTES) { "照片标注点数无效" }
            for (offset in pointsOffset until bytes.size step Float.SIZE_BYTES) {
                check(data.getFloat(offset) in 0f..1f) { "标注点必须位于照片内" }
            }
        }

        override fun get(index: Int): PhotoStroke {
            if (index !in indices) throw IndexOutOfBoundsException(index.toString())
            val start = if (index == 0) 0 else data.getInt(index * Int.SIZE_BYTES)
            val end = data.getInt((index + 1) * Int.SIZE_BYTES)
            return PhotoStroke(object : AbstractList<PhotoPoint>(), RandomAccess {
                override val size = end - start
                override fun get(index: Int): PhotoPoint {
                    if (index !in indices) throw IndexOutOfBoundsException(index.toString())
                    val offset = pointsOffset + (start + index) * POINT_BYTES
                    return PhotoPoint(data.getFloat(offset), data.getFloat(offset + Float.SIZE_BYTES))
                }
            })
        }
    }
}
