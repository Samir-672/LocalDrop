package com.samir.localdrop.util

object FileTools {
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_")
            .trim().ifBlank { "shared-file" }
        return base.take(240)
    }
}
