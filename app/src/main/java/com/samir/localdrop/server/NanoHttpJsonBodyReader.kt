package com.samir.localdrop.server

import com.samir.localdrop.util.JsonBodyParseResult
import com.samir.localdrop.util.JsonRequestParser
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException

object NanoHttpJsonBodyReader {
    fun read(session: NanoHTTPD.IHTTPSession, maxBytes: Long): JsonBodyParseResult {
        val contentType = session.headers["content-type"]
        if (contentType?.startsWith("application/json", ignoreCase = true) == true &&
            !contentType.contains("charset=", ignoreCase = true)
        ) {
            session.headers["content-type"] = "$contentType; charset=UTF-8"
        }
        val contentLength = session.headers["content-length"]?.toLongOrNull()
        if (contentLength != null && contentLength > maxBytes) {
            return JsonBodyParseResult(error = "Request body is too large", status = 413)
        }

        val bodyFiles = mutableMapOf<String, String>()
        try {
            session.parseBody(bodyFiles)
        } catch (e: NanoHTTPD.ResponseException) {
            return JsonBodyParseResult(error = "Could not read request body", status = 400)
        } catch (e: IOException) {
            return JsonBodyParseResult(error = "Could not read request body", status = 400)
        }

        return try {
            val bodyData = bodyFiles["postData"]
                ?: return JsonBodyParseResult(error = "A JSON request body is required")
            val bodyFile = File(bodyData)
            val body = if (bodyFile.isFile) bodyFile.readText(Charsets.UTF_8) else bodyData
            if (body.toByteArray(Charsets.UTF_8).size > maxBytes) {
                JsonBodyParseResult(error = "Request body is too large", status = 413)
            } else {
                JsonRequestParser.parse(body, maxBytes)
            }
        } catch (e: IOException) {
            JsonBodyParseResult(error = "Could not read request body", status = 400)
        }
    }
}
