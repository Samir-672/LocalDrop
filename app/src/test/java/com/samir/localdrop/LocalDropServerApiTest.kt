package com.samir.localdrop

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.samir.localdrop.data.SharedRepository
import com.samir.localdrop.model.SharedItem
import com.samir.localdrop.server.LocalDropServer
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LocalDropServerApiTest {
    private lateinit var context: Context
    private lateinit var repository: SharedRepository
    private lateinit var server: LocalDropServer
    private val gson = Gson()

    @Before
    fun startServer() {
        context = RuntimeEnvironment.getApplication()
        repository = SharedRepository(context)
        repository.load().forEach { repository.delete(it.id) }
        server = LocalDropServer(context, repository, 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun login_authentication_text_and_delete_use_the_real_server_routes() {
        assertEquals(401, request("GET", "/api/items").status)
        assertEquals(401, postJson("/api/login", """{"code":"000000"}""").status)

        val login = postJson("/api/login", gson.toJson(mapOf("code" to server.getAccessCode())))
        assertEquals(200, login.status)
        val token = gson.fromJson(login.body, Map::class.java)["token"] as String
        assertTrue(token.isNotBlank())

        val message = "বাংলা text\nUnicode 🎉"
        val created = postJson("/api/text", gson.toJson(mapOf("text" to message)), token)
        assertEquals(201, created.status)
        val item = gson.fromJson(created.body, Map::class.java)
        assertEquals("text", item["type"])
        assertEquals(message, item["text"])
        assertEquals(0, (item["size"] as Number).toInt())
        assertEquals(null, item["relativePath"])
        assertEquals(null, item["mimeType"])
        val id = item["id"] as String

        val listed = request("GET", "/api/items", token)
        assertEquals(200, listed.status)
        val listedItems = gson.fromJson(listed.body, List::class.java).filterIsInstance<Map<*, *>>()
        assertEquals(message, listedItems.single()["text"])
        assertEquals("text", listedItems.single()["type"])
        assertEquals(message, SharedRepository(context).load().single().text)
        repository.addText("Android-origin text")
        assertTrue(request("GET", "/api/items", token).body.contains("Android-origin text"))
        assertEquals(404, request("DELETE", "/api/items/missing-id", token).status)
        assertEquals(200, request("DELETE", "/api/items/$id", token).status)
        assertEquals(404, request("DELETE", "/api/items/$id", token).status)
        assertEquals(200, request("GET", "/api/items", token).status)
        server.invalidateSessions()
        assertEquals(401, request("GET", "/api/items", token).status)
    }

    @Test
    fun shared_item_gson_names_remain_stable_for_minified_builds() {
        val apiNames = listOf("id", "type", "name", "text", "relativePath", "mimeType", "size", "createdAt")
        val annotations = apiNames.associateWith { name ->
            SharedItem::class.java.getDeclaredField(name).getAnnotation(SerializedName::class.java)?.value
        }

        assertEquals(apiNames.associateWith { it }, annotations)

        val item = SharedItem(
            id = "item-id",
            type = "text",
            name = "Text",
            text = "Release-safe JSON",
            createdAt = 123L
        )
        val jsonFields = gson.fromJson(gson.toJson(item), Map::class.java)
        assertEquals(setOf("id", "type", "name", "text", "size", "createdAt"), jsonFields.keys)
        assertEquals("text", jsonFields["type"])
    }

    @Test
    fun persisted_text_payload_is_normalized_to_a_text_item_without_file_metadata() {
        val text = "Android shared text"
        val legacyText = SharedItem(
            id = "legacy-text",
            type = "file",
            name = "Text",
            text = text,
            createdAt = System.currentTimeMillis()
        )
        File(context.filesDir, "shared-items.json").writeText(gson.toJson(listOf(legacyText)))

        val token = authenticate()
        val listed = request("GET", "/api/items", token)

        assertEquals(200, listed.status)
        val item = gson.fromJson(listed.body, List::class.java).single() as Map<*, *>
        assertEquals("text", item["type"])
        assertEquals(text, item["text"])
        assertEquals(0, (item["size"] as Number).toInt())
        assertEquals(null, item["relativePath"])
        assertEquals(null, item["mimeType"])
        assertTrue(File(context.filesDir, "shared-items.json").readText().contains("\"type\":\"text\""))
    }

    @Test
    fun authenticated_multiple_upload_and_large_file_download_preserve_bytes() {
        val token = authenticate()
        val large = ByteArray(2 * 1024 * 1024 + 37) { (it * 31).toByte() }
        val small = "small file".toByteArray(Charsets.UTF_8)
        val boundary = "localdrop-test-boundary"
        val upload = multipart(
            boundary,
            listOf(
                Triple("file0", "archive-資料.bin", "application/octet-stream" to large),
                Triple("file1", "note.txt", "text/plain" to small)
            )
        )
        val uploaded = request(
            "POST",
            "/api/upload",
            token,
            "multipart/form-data; boundary=$boundary",
            upload
        )
        assertEquals(201, uploaded.status)
        val items = gson.fromJson(uploaded.body, List::class.java).filterIsInstance<Map<*, *>>()
        assertEquals(2, items.size)

        val fileItem = items.firstOrNull { it["name"] == "archive-資料.bin" }
        assertNotNull(uploaded.body, fileItem)
        fileItem!!
        assertEquals(large.size.toLong(), (fileItem["size"] as Number).toLong())
        assertEquals("application/octet-stream", fileItem["mimeType"])
        val noteItem = items.firstOrNull { it["name"] == "note.txt" }
        assertNotNull(uploaded.body, noteItem)
        assertEquals("text/plain", noteItem!!["mimeType"])
        val id = fileItem["id"] as String
        assertEquals(401, request("GET", "/api/files/$id").status)

        val download = open("GET", "/api/files/$id", token)
        assertEquals(200, download.responseCode)
        assertTrue(download.getHeaderField("Content-Disposition").contains("filename*=UTF-8''"))
        val digest = MessageDigest.getInstance("SHA-256")
        download.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        download.disconnect()
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(large),
            digest.digest()
        )
        assertNotNull(fileItem["mimeType"])

        val storedFile = repository.safeFile(fileItem["relativePath"] as String)!!
        assertTrue(storedFile.delete())
        val missing = request("GET", "/api/files/$id", token)
        assertEquals(404, missing.status)
        assertTrue(missing.body.contains("File not found"))
    }

    @Test
    fun empty_upload_is_rejected_without_creating_file_metadata() {
        val token = authenticate()
        val boundary = "localdrop-empty-file-boundary"
        val upload = multipart(
            boundary,
            listOf(Triple("file0", "empty.txt", "text/plain" to byteArrayOf()))
        )

        val response = request(
            "POST",
            "/api/upload",
            token,
            "multipart/form-data; boundary=$boundary",
            upload
        )

        assertEquals(400, response.status)
        assertTrue(response.body.contains("Missing or empty files"))
        assertTrue(repository.load().isEmpty())
        assertTrue(repository.sharedDir.listFiles()!!.isEmpty())
    }

    @Test
    fun repository_rejects_missing_or_empty_android_file_sources() {
        val emptyFile = File(context.cacheDir, "localdrop-empty-source").apply { createNewFile() }
        val missingFile = File(context.cacheDir, "localdrop-missing-source")

        assertThrows(IOException::class.java) {
            repository.addFile("empty.txt", emptyFile, "text/plain")
        }
        assertThrows(IOException::class.java) {
            repository.addFile("missing.txt", missingFile, "text/plain")
        }

        assertTrue(repository.load().isEmpty())
        assertTrue(repository.sharedDir.listFiles()!!.isEmpty())
        emptyFile.delete()
    }

    @Test
    fun clear_all_removes_shared_files_and_records_without_invalidating_server_or_session() {
        val token = authenticate()
        val accessCode = server.getAccessCode()
        repository.addText("First shared text")
        repository.addText("Second shared text")
        val source = File(context.cacheDir, "clear-all-source.bin").apply {
            writeBytes("shared file bytes".toByteArray())
        }
        val fileItem = repository.addFile("clear-me.bin", source, "application/octet-stream")
        source.delete()
        val storedFile = repository.safeFile(fileItem.relativePath!!)!!
        File(repository.sharedDir, "orphan.tmp").writeText("orphaned private file")
        assertTrue(storedFile.isFile)
        assertEquals(3, repository.load().size)

        assertEquals(401, request("DELETE", "/api/items").status)
        assertEquals(3, repository.load().size)
        assertTrue(storedFile.isFile)

        val cleared = request("DELETE", "/api/items", token)
        assertEquals(200, cleared.status)
        val result = gson.fromJson(cleared.body, Map::class.java)
        assertEquals(true, result["ok"])
        assertEquals(3, (result["deletedCount"] as Number).toInt())
        assertTrue(repository.load().isEmpty())
        assertEquals("[]", File(context.filesDir, "shared-items.json").readText())
        assertFalse(storedFile.exists())
        assertTrue(repository.sharedDir.listFiles()!!.isEmpty())
        assertTrue(server.isAlive)
        assertEquals(accessCode, server.getAccessCode())
        assertEquals(200, request("GET", "/api/items", token).status)

        assertEquals(201, postJson("/api/text", """{"text":"New text after clear"}""", token).status)
        val boundary = "clear-all-followup-boundary"
        val upload = multipart(
            boundary,
            listOf(Triple("file0", "new.txt", "text/plain" to "new file".toByteArray()))
        )
        assertEquals(
            201,
            request(
                "POST",
                "/api/upload",
                token,
                "multipart/form-data; boundary=$boundary",
                upload
            ).status
        )
        assertEquals(2, repository.load().size)
        assertEquals(1, repository.sharedDir.listFiles()!!.size)
        repository.clearAll()
    }

    @Test
    fun clear_all_failure_keeps_existing_metadata_and_files() {
        val token = authenticate()
        repository.addText("Keep this text")
        val source = File(context.cacheDir, "clear-all-failure-source.bin").apply {
            writeBytes("keep this file".toByteArray())
        }
        val fileItem = repository.addFile("keep.bin", source, "application/octet-stream")
        source.delete()
        val storedFile = repository.safeFile(fileItem.relativePath!!)!!
        val metadataFile = File(context.filesDir, "shared-items.json")
        val originalMetadata = metadataFile.readText()
        val invalidItems = repository.load().map {
            if (it.id == fileItem.id) it.copy(relativePath = "../outside.bin") else it
        }
        val invalidMetadata = gson.toJson(invalidItems)
        metadataFile.writeText(invalidMetadata)

        val result = request("DELETE", "/api/items", token)

        assertEquals(500, result.status)
        assertEquals(invalidMetadata, metadataFile.readText())
        assertEquals(2, repository.load().size)
        assertTrue(storedFile.isFile)
        metadataFile.writeText(originalMetadata)
        repository.clearAll()
    }

    private fun authenticate(): String {
        val result = postJson("/api/login", gson.toJson(mapOf("code" to server.getAccessCode())))
        assertEquals(200, result.status)
        return gson.fromJson(result.body, Map::class.java)["token"] as String
    }

    private fun postJson(path: String, body: String, token: String? = null) =
        request("POST", path, token, "application/json; charset=UTF-8", body.toByteArray())

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        contentType: String? = null,
        body: ByteArray? = null
    ): HttpResult {
        val connection = open(method, path, token, contentType, body)
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        connection.disconnect()
        return HttpResult(status, responseBody)
    }

    private fun open(
        method: String,
        path: String,
        token: String? = null,
        contentType: String? = null,
        body: ByteArray? = null
    ): HttpURLConnection {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.requestMethod = method
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return connection
    }

    private fun multipart(
        boundary: String,
        files: List<Triple<String, String, Pair<String, ByteArray>>>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        for ((field, name, content) in files) {
            val (mime, bytes) = content
            output.write("--$boundary\r\n".toByteArray())
            val metadata = gson.toJson(mapOf("name" to name, "mimeType" to mime))
            val encodedMetadata = java.util.Base64.getEncoder().encodeToString(metadata.toByteArray(Charsets.UTF_8))
            output.write("Content-Disposition: form-data; name=\"$field\"; filename=\"ldmeta1-$encodedMetadata\"\r\n".toByteArray())
            output.write("Content-Type: $mime\r\n\r\n".toByteArray())
            output.write(bytes)
            output.write("\r\n".toByteArray())
        }
        output.write("--$boundary--\r\n".toByteArray())
        return output.toByteArray()
    }

    private data class HttpResult(val status: Int, val body: String)
}
