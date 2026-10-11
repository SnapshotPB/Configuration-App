package com.snapshotpb.configuration.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MediaRequestManager {
    private val _bus = MutableSharedFlow<MediaRequest>()
    val bus: SharedFlow<MediaRequest> = _bus

    suspend fun send(mediaRequest: MediaRequest) =
        _bus.emit(mediaRequest)
}