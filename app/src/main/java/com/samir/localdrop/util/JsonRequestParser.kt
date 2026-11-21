package com.samir.localdrop.util

import com.google.gson.Gson
import com.google.gson.JsonParseException

data class JsonBodyParseResult(
    val values: Map<String, String> = emptyMap(),
    val error: String? = null,
    val status: Int = 400
)

object JsonRequestParser {
    private val gson = Gson()

    fun parse(body: String, maxBytes: Long): JsonBodyParseResult {
        if (body.toByteArray(Charsets.UTF_8).size > maxBytes) {
            return JsonBodyParseResult(error = "Request body is too large", status = 413)
        }
        return try {
            val parsed = gson.fromJson(body, Map::class.java)
                ?: return JsonBodyParseResult(error = "Invalid JSON request body")
            val values = parsed.entries.associate { (key, value) ->
                key.toString() to (value?.toString() ?: "")
            }
            JsonBodyParseResult(values = values)
        } catch (e: JsonParseException) {
            JsonBodyParseResult(error = "Invalid JSON request body")
        }
    }
}
