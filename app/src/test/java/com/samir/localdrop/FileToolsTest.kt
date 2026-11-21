package com.samir.localdrop

import com.samir.localdrop.util.FileTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileToolsTest {
    @Test fun strips_path_and_replaces_invalid_characters() {
        assertEquals("report_2026.txt", FileTools.sanitize("../report?2026.txt"))
        assertFalse(FileTools.sanitize("../../bad.txt").contains('/'))
    }

    @Test fun blank_name_has_safe_fallback() {
        assertEquals("shared-file", FileTools.sanitize("   "))
    }

    @Test fun unicode_filename_is_preserved() {
        assertEquals("বাংলা-文档.txt", FileTools.sanitize("বাংলা-文档.txt"))
    }
}
