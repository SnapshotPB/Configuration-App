package com.snapshotpb.configuration.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class MediaStateManager {
    private val _mediaState: MutableStateFlow<MediaState> =
        MutableStateFlow(MediaState.NeedsPermission)
    val mediaState: StateFlow<MediaState> get() = _mediaState

    fun update(mediaState: MediaState) = _mediaState.update { mediaState }
}