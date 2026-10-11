package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.common.media.MediaSelectionState
import com.snapshotpb.configuration.model.Photo

/**
 * Renders the device add/configure media controls.
 *
 * On platforms with an image picker (Android / iOS / desktop) this surfaces the camera and gallery
 * launchers via imagepickerkmp. The web has no usable imagepickerkmp wasm API, so it uses the file
 * input of the browser (see the wasm actual).
 */
@Composable
internal expect fun MediaSelection(
    photo: Photo?,
    fallbackPhotoPath: String = "",
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onSave: () -> Unit,
    mediaSelectionState: MediaSelectionState,
    onPhotoSelected: (Photo) -> Unit,
    onEndMediaSelection: () -> Unit,
    onShowImagePicker: () -> Unit,
    onShowGalleryPicker: () -> Unit,
)

/**
 * Whether this platform can capture/select a device photo and keep it. On the web, it is false in a
 * browser without IndexedDB or `createImageBitmap`, so callers hide the camera/gallery affordances
 * instead of showing inert buttons.
 */
internal expect val mediaCaptureSupported: Boolean