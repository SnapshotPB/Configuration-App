package com.snapshotpb.configuration.media

sealed interface MediaRequest {
    object RequestPermission : MediaRequest
}
