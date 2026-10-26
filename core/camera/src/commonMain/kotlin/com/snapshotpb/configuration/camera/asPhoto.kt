package com.snapshotpb.configuration.camera

import com.snapshotpb.configuration.model.Photo
import io.github.ismoy.imagepickerkmp.domain.models.PhotoResult

internal fun PhotoResult.asPhoto() = Photo(
    uri = uri,
    width = width,
    height = height,
    fileName = fileName,
    fileSize = fileSize,
    mimeType = mimeType,
)
