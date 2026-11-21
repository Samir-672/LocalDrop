package com.samir.localdrop.model

data class SharedItem(
    val id: String,
    val type: String,
    val name: String,
    val text: String? = null,
    val relativePath: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val createdAt: Long
)
