package com.baconaa.folio.records

import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoStrokeStorageTest {
    @Test fun invalidPointsAndDamagedStrokeDataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhotoPoint(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PhotoPoint(0f, 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { PhotoStroke(emptyList()) }

        val bytes = PhotoStrokeStorage.encode(listOf(PhotoStroke(listOf(PhotoPoint(0.2f, 0.8f)))))!!
        assertThrows(IllegalStateException::class.java) { PhotoStrokeStorage.decode(bytes.copyOf(bytes.size - 1)) }
        val badEnd = bytes.clone()
        ByteBuffer.wrap(badEnd).putInt(Int.SIZE_BYTES, 2)
        assertThrows(IllegalStateException::class.java) { PhotoStrokeStorage.decode(badEnd) }
        ByteBuffer.wrap(bytes).putFloat(bytes.size - Float.SIZE_BYTES, Float.POSITIVE_INFINITY)
        assertThrows(IllegalStateException::class.java) { PhotoStrokeStorage.decode(bytes) }
    }
}
