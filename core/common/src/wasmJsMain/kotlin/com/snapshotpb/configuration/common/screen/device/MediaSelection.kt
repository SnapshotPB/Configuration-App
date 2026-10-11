package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.snapshotpb.configuration.common.media.MediaSelectionState
import com.snapshotpb.configuration.common.media.pickWebPhoto
import com.snapshotpb.configuration.common.media.webPhotoCaptureSupported
import com.snapshotpb.configuration.common.screen.device.composable.AddDeviceScreenContent
import com.snapshotpb.configuration.model.Photo

/**
 * Web actual. imagepickerkmp has no usable wasm API, so the web uses the file input of the browser
 * (see [pickWebPhoto]). [MediaSelectionState.Camera] adds `capture` to the input, so a mobile
 * browser opens the camera. [MediaSelectionState.Gallery] shows the file chooser.
 *
 * The chooser opens when the state changes to Camera or Gallery, and the selection ends when the
 * user selects a file or closes the chooser. The base content stays in view below the chooser.
 * The photo buttons show only when [mediaCaptureSupported] is true.
 *
 * A browser without the `cancel` event of the input does not tell when the user closes the
 * chooser, so the state can stay Camera or Gallery. A tap on the button of the current state then
 * does not change the state. So that tap opens a new chooser here, through `chooserRequest`.
 */
@Composable
internal actual fun MediaSelection(
    photo: Photo?,
    fallbackPhotoPath: String,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onSave: () -> Unit,
    mediaSelectionState: MediaSelectionState,
    onPhotoSelected: (Photo) -> Unit,
    onEndMediaSelection: () -> Unit,
    onShowImagePicker: () -> Unit,
    onShowGalleryPicker: () -> Unit,
) {
    var chooserRequest by remember { mutableIntStateOf(0) }
    AddDeviceScreenContent(
        photo = photo,
        fallbackPhotoPath = fallbackPhotoPath,
        deviceName = deviceName,
        onDeviceNameChange = onDeviceNameChange,
        onSave = onSave,
        onShowImagePicker = {
            if (mediaSelectionState == MediaSelectionState.Camera) chooserRequest++ else onShowImagePicker()
        },
        onShowGalleryPicker = {
            if (mediaSelectionState == MediaSelectionState.Gallery) chooserRequest++ else onShowGalleryPicker()
        },
        addToEnd = {},
        showMediaButtons = mediaCaptureSupported,
    )

    if (mediaCaptureSupported && mediaSelectionState != MediaSelectionState.NoSelection) {
        val currentOnPhotoSelected by rememberUpdatedState(onPhotoSelected)
        val currentOnEndMediaSelection by rememberUpdatedState(onEndMediaSelection)
        // A new key cancels the old chooser, which removes its input (see pickWebPhoto).
        LaunchedEffect(mediaSelectionState, chooserRequest) {
            pickWebPhoto(camera = mediaSelectionState == MediaSelectionState.Camera)
                ?.let { selected -> currentOnPhotoSelected(selected) }
            currentOnEndMediaSelection()
        }
    }
}

internal actual val mediaCaptureSupported: Boolean = webPhotoCaptureSupported()
