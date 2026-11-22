package com.samir.localdrop.model

import com.google.gson.annotations.SerializedName

data class SharedItem(
    @field:SerializedName("id")
    val id: String,
    @field:SerializedName("type")
    val type: String,
    @field:SerializedName("name")
    val name: String,
    @field:SerializedName("text")
    val text: String? = null,
    @field:SerializedName("relativePath")
    val relativePath: String? = null,
    @field:SerializedName("mimeType")
    val mimeType: String? = null,
    @field:SerializedName("size")
    val size: Long = 0,
    @field:SerializedName("createdAt")
    val createdAt: Long
)
