package com.samir.localdrop

import com.samir.localdrop.util.JsonRequestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JsonRequestParserTest {
    @Test fun parses_login_code_and_unicode_text() {
        val result = JsonRequestParser.parse("""{"code":"123456","text":"বাংলা 👋"}""", 100)
        assertNull(result.error)
        assertEquals("123456", result.values["code"])
        assertEquals("বাংলা 👋", result.values["text"])
    }

    @Test fun malformed_json_returns_client_error() {
        val result = JsonRequestParser.parse("""{"code":""", 100)
        assertNotNull(result.error)
        assertEquals(400, result.status)
    }

    @Test fun oversized_json_returns_payload_too_large() {
        val result = JsonRequestParser.parse("""{"code":"123456"}""", 5)
        assertEquals("Request body is too large", result.error)
        assertEquals(413, result.status)
    }
}
