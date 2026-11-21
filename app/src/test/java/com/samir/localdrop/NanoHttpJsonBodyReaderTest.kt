package com.samir.localdrop

import com.samir.localdrop.server.NanoHttpJsonBodyReader
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class NanoHttpJsonBodyReaderTest {
    @Test
    fun reads_json_request_without_waiting_for_keep_alive_socket_close() {
        val server = BodyProbeServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val connection = URL("http://127.0.0.1:${server.listeningPort}/login").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = """{"code":"654321"}""".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val response = (if (status < 400) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            assertEquals(response, 200, status)
            assertEquals("654321", response)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    private class BodyProbeServer : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            val body = NanoHttpJsonBodyReader.read(session, 128)
            if (body.error != null) {
                return newFixedLengthResponse(
                    Response.Status.lookup(body.status),
                    "application/json",
                    body.error
                )
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", body.values["code"] ?: "")
        }
    }
}
