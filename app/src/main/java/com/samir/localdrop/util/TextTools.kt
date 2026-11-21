package com.samir.localdrop.util

object TextTools {
    fun preview(text: String, limit: Int = 150): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= limit) compact else compact.take(limit).trimEnd() + "..."
    }
}
