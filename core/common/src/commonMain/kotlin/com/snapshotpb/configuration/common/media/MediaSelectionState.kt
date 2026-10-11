package com.snapshotpb.configuration.common.media

sealed interface MediaSelectionState {
    object NoSelection : MediaSelectionState
    object Camera : MediaSelectionState
    object Gallery : MediaSelectionState
}
