package com.samir.localdrop.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.samir.localdrop.model.SharedItem
import com.samir.localdrop.util.TextTools
import com.samir.localdrop.util.FileTools
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SharedRepository(private val context: Context) {
    private val gson = Gson()
    private val metadataFile = File(context.filesDir, "shared-items.json")
    val sharedDir = File(context.filesDir, "shared").apply { mkdirs() }
    private val lock = locks.computeIfAbsent(metadataFile.absolutePath) { Any() }

    @Synchronized
    fun load(): MutableList<SharedItem> = synchronized(lock) {
        if (!metadataFile.exists()) return mutableListOf()
        val type = object : TypeToken<MutableList<SharedItem>>() {}.type
        gson.fromJson(metadataFile.readText(Charsets.UTF_8), type) ?: mutableListOf()
    }

    private fun save(items: List<SharedItem>) {
        val temp = File(context.filesDir, "shared-items.tmp")
        temp.writeText(gson.toJson(items), Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                metadataFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun addText(text: String): SharedItem = synchronized(lock) {
        val items = load()
        val item = SharedItem(
            id = UUID.randomUUID().toString(),
            type = "text",
            name = "Text",
            text = text,
            createdAt = System.currentTimeMillis()
        )
        items.add(0, item)
        save(items)
        item
    }

    fun addFile(name: String, source: File, mimeType: String?): SharedItem = synchronized(lock) {
        val items = load()
        val safeName = sanitize(name)
        val uniqueName = uniqueName(safeName)
        val target = File(sharedDir, uniqueName)
        source.copyTo(target, overwrite = false)
        val item = SharedItem(
            id = UUID.randomUUID().toString(),
            type = "file",
            name = uniqueName,
            relativePath = uniqueName,
            mimeType = mimeType ?: "application/octet-stream",
            size = target.length(),
            createdAt = System.currentTimeMillis()
        )
        items.add(0, item)
        save(items)
        item
    }

    fun addFileBytes(name: String, bytes: ByteArray, mimeType: String?): SharedItem =
        addFile(name, File.createTempFile("upload-", ".tmp", context.cacheDir).apply {
            writeBytes(bytes)
        }, mimeType)

    fun delete(id: String): Boolean = synchronized(lock) {
        val items = load()
        val item = items.firstOrNull { it.id == id } ?: return false
        if (item.type == "file" && item.relativePath != null) {
            val file = safeFile(item.relativePath)
            if (file != null) file.delete()
        }
        val removed = items.removeAll { it.id == id }
        if (removed) save(items)
        removed
    }

    fun clearAll(): Int = synchronized(lock) {
        val items = load()
        val root = sharedDir.canonicalFile
        if (root.parentFile != context.filesDir.canonicalFile) {
            throw IOException("Shared file storage is outside the application storage directory")
        }
        items.filter { it.type == "file" && it.relativePath != null }.forEach { item ->
            val file = safeFile(item.relativePath!!)
                ?: throw IOException("A shared file path is invalid")
            if (file.exists() && !file.isFile) {
                throw IOException("A shared file cannot be accessed")
            }
        }
        val entries = sharedDir.listFiles()
            ?: throw IOException("Could not list shared file storage")
        if (entries.isEmpty()) {
            if (items.isNotEmpty()) save(emptyList())
            return items.size
        }

        val staging = File(context.filesDir, ".shared-clear-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Could not prepare shared file cleanup")
        val moved = mutableListOf<Pair<File, File>>()
        try {
            entries.forEachIndexed { index, entry ->
                val staged = File(staging, index.toString())
                movePath(entry, staged)
                moved.add(entry to staged)
            }
            save(emptyList())
        } catch (failure: Exception) {
            restoreClear(items, moved, staging, failure)
            throw failure
        }

        if (!staging.deleteRecursively()) {
            val failure = IOException("Could not remove shared file data")
            if (moved.all { it.second.exists() }) {
                restoreClear(items, moved, staging, failure)
            }
            throw failure
        }
        items.size
    }

    fun safeFile(relativePath: String): File? {
        val file = File(sharedDir, relativePath).canonicalFile
        val root = sharedDir.canonicalFile
        return if (file.path.startsWith(root.path + File.separator)) file else null
    }

    fun sanitize(name: String): String {
        return FileTools.sanitize(name)
    }

    private fun uniqueName(name: String): String {
        if (!File(sharedDir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (File(sharedDir, "${stem} (${n})$ext").exists()) n++
        return "${stem} (${n})$ext"
    }

    private fun restoreClear(
        items: List<SharedItem>,
        moved: List<Pair<File, File>>,
        staging: File,
        failure: Exception
    ) {
        moved.asReversed().forEach { (original, staged) ->
            if (staged.exists()) {
                try {
                    movePath(staged, original, replaceExisting = true)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
        }
        try {
            save(items)
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
        }
        if (staging.exists()) {
            val remaining = staging.listFiles()
            if (remaining == null || (remaining.isEmpty() && !staging.delete())) {
                failure.addSuppressed(IOException("Could not remove temporary shared file cleanup data"))
            }
        }
    }

    private fun movePath(source: File, destination: File, replaceExisting: Boolean = false) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source.toPath(), destination.toPath(), *fallback)
        }
    }

    fun preview(text: String, limit: Int = 150): String = TextTools.preview(text, limit)

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
