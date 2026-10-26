package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.common.media.ImagePicker
import com.snapshotpb.configuration.common.media.MediaSelectionState
import com.snapshotpb.configuration.common.screen.device.composable.AddDeviceScreenContent
import com.snapshotpb.configuration.model.Photo
import org.kodein.di.compose.rememberInstance

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
    val imagePicker: ImagePicker by rememberInstance()
    when (mediaSelectionState) {
        MediaSelectionState.Camera ->
            imagePicker.CameraLauncher(
                onPhotoSelected = onPhotoSelected,
                onEndMediaSelection = onEndMediaSelection,
            )

        MediaSelectionState.Gallery ->
            AddDeviceScreenContent(
                photo = photo,
                fallbackPhotoPath = fallbackPhotoPath,
                deviceName = deviceName,
                onDeviceNameChange = onDeviceNameChange,
                onSave = onSave,
                onShowImagePicker = onShowImagePicker,
                onShowGalleryPicker = onShowGalleryPicker,
                addToEnd = {
                    imagePicker.GalleryLauncher(
                        onPhotoSelected = onPhotoSelected,
                        onEndMediaSelection = onEndMediaSelection,
                    )
                },
            )

        MediaSelectionState.NoSelection -> AddDeviceScreenContent(
            photo = photo,
            fallbackPhotoPath = fallbackPhotoPath,
            deviceName = deviceName,
            onDeviceNameChange = onDeviceNameChange,
            onSave = onSave,
            onShowImagePicker = onShowImagePicker,
            onShowGalleryPicker = onShowGalleryPicker,
            addToEnd = {},
        )
    }
}

internal actual val mediaCaptureSupported: Boolean = true
