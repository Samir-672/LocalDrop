package com.samir.localdrop

import com.samir.localdrop.util.TextTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test
    fun preserves_bengali_unicode_and_emoji() {
        val text = "বিষয়বস্তু\nHello 世界 👋"
        assertEquals(text, text)
        assertTrue(text.contains("বিষ"))
        assertTrue(text.contains("世界"))
        assertTrue(text.contains("👋"))
    }

    @Test
    fun preview_is_limited_to_two_line_length() {
        val text = "A".repeat(300)
        val preview = TextTools.preview(text)
        assertEquals(153, preview.length)
    }

    @Test
    fun preview_does_not_change_original() {
        val text = "  First line\nSecond line  "
        assertEquals("  First line\nSecond line  ", text)
        assertEquals("First line Second line", TextTools.preview(text))
    }
}
