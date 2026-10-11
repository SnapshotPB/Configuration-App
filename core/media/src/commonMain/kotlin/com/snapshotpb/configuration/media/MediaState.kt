package com.snapshotpb.configuration.media

sealed interface MediaState {
    object NeedsPermission : MediaState
    object Ready : MediaState
}
