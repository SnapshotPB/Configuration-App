package com.snapshotpb.configuration.common.media

import com.snapshotpb.configuration.model.Photo
import io.github.ismoy.imagepickerkmp.domain.models.PhotoResult

fun PhotoResult.asPhoto() = Photo(
    uri = uri,
    width = width,
    height = height,
    fileName = fileName,
    fileSize = fileSize,
    mimeType = mimeType,
)