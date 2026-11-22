package com.samir.localdrop.server

import android.content.Context
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import com.samir.localdrop.data.SharedRepository
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

class LocalDropServer(
    private val context: Context,
    val repository: SharedRepository,
    port: Int = 8080
) : NanoHTTPD(port) {
    private val gson = Gson()
    private var accessCode = generateCode()
    private val tokens = ConcurrentHashMap<String, Long>()
    private val sessionDuration = 30.minutes.inWholeMilliseconds

    fun getAccessCode(): String = accessCode

    fun regenerateAccessCode() {
        accessCode = generateCode()
        tokens.clear()
    }

    fun invalidateSessions() {
        tokens.clear()
    }

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        if (uri.isEmpty() || uri == "/") return assetResponse("index.html")
        if (uri.startsWith("/assets/")) {
            val path = uri.removePrefix("/assets/")
            return assetResponse(path)
        }
        if (session.method == Method.GET && uri == "/api/status") {
            return jsonResponse(200, mapOf("running" to true))
        }
        if (session.method == Method.POST && uri == "/api/login") {
            val body = NanoHttpJsonBodyReader.read(session, MAX_JSON_BYTES)
            if (body.error != null) return jsonResponse(body.status, mapOf("error" to body.error))
            if ((body.values["code"] ?: "") != accessCode) return jsonResponse(401, mapOf("error" to "Invalid access code"))
            val token = generateToken()
            tokens[token] = System.currentTimeMillis() + sessionDuration
            return jsonResponse(200, mapOf("token" to token))
        }
        if (session.method == Method.POST && uri == "/api/logout") {
            val token = bearerToken(session)
            if (token != null) tokens.remove(token)
            return jsonResponse(200, mapOf("ok" to true))
        }
        val token = bearerToken(session)
        if (!isAuthorized(token)) return jsonResponse(401, mapOf("error" to "Unauthorized"))

        return when {
            session.method == Method.GET && uri == "/api/items" ->
                try {
                    jsonResponse(200, repository.load())
                } catch (_: Exception) {
                    jsonResponse(500, mapOf("error" to "Could not read shared items"))
                }
            session.method == Method.POST && uri == "/api/text" -> {
                val body = NanoHttpJsonBodyReader.read(session, MAX_JSON_BYTES)
                if (body.error != null) return jsonResponse(body.status, mapOf("error" to body.error))
                val text = body.values["text"] ?: ""
                if (text.isBlank()) jsonResponse(400, mapOf("error" to "Text cannot be empty"))
                else try {
                    jsonResponse(201, repository.addText(text))
                } catch (_: Exception) {
                    jsonResponse(500, mapOf("error" to "Could not save text"))
                }
            }
            session.method == Method.POST && uri == "/api/upload" ->
                upload(session)
            session.method == Method.DELETE && uri == "/api/items" ->
                try {
                    jsonResponse(200, mapOf("ok" to true, "deletedCount" to repository.clearAll()))
                } catch (_: Exception) {
                    jsonResponse(500, mapOf("error" to "Could not clear shared items"))
                }
            session.method == Method.DELETE && uri.startsWith("/api/items/") -> {
                val id = uri.substringAfterLast("/")
                try {
                    if (repository.delete(id)) jsonResponse(200, mapOf("ok" to true))
                    else jsonResponse(404, mapOf("error" to "Item not found"))
                } catch (_: Exception) {
                    jsonResponse(500, mapOf("error" to "Could not delete item"))
                }
            }
            session.method == Method.GET && uri.startsWith("/api/files/") -> {
                val id = uri.substringAfterLast("/")
                val item = try {
                    repository.load().firstOrNull { it.id == id }
                } catch (_: Exception) {
                    return jsonResponse(500, mapOf("error" to "Could not read shared item"))
                }
                if (item == null || item.type != "file" || item.relativePath == null) {
                    jsonResponse(404, mapOf("error" to "File not found"))
                } else {
                    val file = repository.safeFile(item.relativePath!!)
                    if (file == null || !file.isFile) jsonResponse(404, mapOf("error" to "File not found"))
                    else if (file.length() <= 0L) jsonResponse(404, mapOf("error" to "File is empty"))
                    else newFixedLengthResponse(
                        Status.OK,
                        item.mimeType ?: "application/octet-stream",
                        file.inputStream(),
                        file.length()
                    ).apply {
                        val encodedName = java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                        addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
                        addHeader("Accept-Ranges", "bytes")
                    }
                }
            }
            else -> jsonResponse(404, mapOf("error" to "Not found"))
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
            return jsonResponse(400, mapOf("error" to "Could not read uploaded files"))
        }
        if (files.isEmpty()) return jsonResponse(400, mapOf("error" to "No files uploaded"))
        if (files.values.any { path ->
                val file = File(path)
                !file.isFile || file.length() <= 0L
            }
        ) {
            return jsonResponse(400, mapOf("error" to "Missing or empty files cannot be shared"))
        }
        return try {
            val uploaded = mutableListOf<com.samir.localdrop.model.SharedItem>()
            for ((fieldName, tempPath) in files) {
                val temp = File(tempPath)
                val encodedName = session.getParms()["${fieldName}__name_b64"]
                val partName = session.getParms()[fieldName]
                    ?: session.getParameters()[fieldName]?.let { if (it.isNotEmpty()) it[0] else null }
                    ?: temp.name
                val metadata = try {
                    decodeUploadMetadata(encodedName, partName)
                } catch (_: IllegalArgumentException) {
                    return jsonResponse(400, mapOf("error" to "Invalid uploaded file metadata"))
                } catch (_: com.google.gson.JsonParseException) {
                    return jsonResponse(400, mapOf("error" to "Invalid uploaded file metadata"))
                }
                val originalName = metadata?.name ?: partName
                val mime = metadata?.mimeType?.takeIf(::isValidMimeType) ?: mimeTypeFor(originalName)
                val item = repository.addFile(originalName, temp, mime)
                uploaded.add(item)
            }
            jsonResponse(201, uploaded)
        } catch (e: Exception) {
            jsonResponse(500, mapOf("error" to "Could not store uploaded files"))
        }
    }

    private fun bearerToken(session: IHTTPSession): String? =
        session.headers["authorization"]?.removePrefix("Bearer ")?.trim()

    private fun isAuthorized(token: String?): Boolean {
        val expiry = token?.let(tokens::get) ?: return false
        if (expiry < System.currentTimeMillis()) {
            tokens.remove(token)
            return false
        }
        return true
    }

    private fun assetResponse(path: String): Response {
        val clean = path.trimStart('/').ifBlank { "index.html" }
        return try {
            val stream = context.assets.open(clean)
            val mime = when (clean.substringAfterLast('.', "")) {
                "html" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "svg" -> "image/svg+xml"
                else -> "application/octet-stream"
            }
            newFixedLengthResponse(Status.OK, mime, stream, stream.available().toLong())
        } catch (_: Exception) {
            jsonResponse(404, mapOf("error" to "Resource not found"))
        }
    }

    private fun jsonResponse(status: Int, value: Any): Response =
        newFixedLengthResponse(Status.lookup(status), "application/json; charset=UTF-8", gson.toJson(value))

    private fun decodeUploadMetadata(encodedField: String?, partName: String): UploadMetadata? {
        val encoded = when {
            encodedField != null -> encodedField to false
            partName.startsWith(ENCODED_METADATA_PREFIX) ->
                partName.removePrefix(ENCODED_METADATA_PREFIX) to true
            partName.startsWith(ENCODED_FILENAME_PREFIX) ->
                partName.removePrefix(ENCODED_FILENAME_PREFIX) to false
            else -> return null
        }
        val decoded = String(java.util.Base64.getDecoder().decode(encoded.first), Charsets.UTF_8)
        if (!encoded.second) return UploadMetadata(decoded)
        val parsed = gson.fromJson(decoded, Map::class.java)
            ?: throw IllegalArgumentException("Missing upload metadata")
        val name = parsed["name"] as? String ?: throw IllegalArgumentException("Missing filename")
        return UploadMetadata(name, parsed["mimeType"] as? String)
    }

    private fun isValidMimeType(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9!#&^_.+-]+/[A-Za-z0-9!#&^_.+-]+"))

    private fun mimeTypeFor(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "txt", "log" -> "text/plain"
                "json" -> "application/json"
                "csv" -> "text/csv"
                else -> "application/octet-stream"
            }
    }

    private fun generateCode(): String {
        val random = SecureRandom()
        return (100000 + random.nextInt(900000)).toString()
    }

    private fun generateToken(): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    companion object {
        private const val MAX_JSON_BYTES = 1_000_000L
        private const val ENCODED_FILENAME_PREFIX = "ld64-"
        private const val ENCODED_METADATA_PREFIX = "ldmeta1-"

        private data class UploadMetadata(val name: String, val mimeType: String? = null)

        fun findWifiAddress(): String? {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (network in interfaces) {
                if (!network.isUp || network.isLoopback) continue
                val name = network.name.lowercase()
                if (!name.contains("wlan") && !name.contains("ap")) continue
                val addresses = Collections.list(network.inetAddresses)
                val address = addresses.firstOrNull {
                    it is java.net.Inet4Address && it.hostAddress?.startsWith("127.") == false
                } as? InetAddress
                if (address != null) return address.hostAddress
            }
            val fallback = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is java.net.Inet4Address } as? InetAddress
            return fallback?.hostAddress
        }
    }
}
