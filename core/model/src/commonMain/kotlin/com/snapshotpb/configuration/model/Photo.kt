package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

@Serializable
data class Photo(
    val uri: String,
    val width: Int? = null,
    val height: Int? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
)
