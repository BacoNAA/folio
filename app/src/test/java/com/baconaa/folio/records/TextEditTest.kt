package com.baconaa.folio.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextEditTest {
    @Test
    fun acceptsUtf16CursorAtEndOfText() {
        val edit = TextEdit("桥🌉", 3)

        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun rejectsCursorOutsideText() {
        assertThrows(IllegalArgumentException::class.java) { TextEdit("桥", -1) }
        assertThrows(IllegalArgumentException::class.java) { TextEdit("桥", 2) }
    }
}
