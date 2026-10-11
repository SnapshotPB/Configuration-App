package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.common.media.MediaSelectionState
import com.snapshotpb.configuration.common.media.asPhoto
import com.snapshotpb.configuration.common.screen.device.composable.AddDeviceScreenContent
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.gallery_error_mime_png_only
import com.toxicbakery.logging.Arbor
import io.github.ismoy.imagepickerkmp.domain.config.CameraCaptureConfig
import io.github.ismoy.imagepickerkmp.domain.config.CropConfig
import io.github.ismoy.imagepickerkmp.domain.config.ImagePickerConfig
import io.github.ismoy.imagepickerkmp.domain.models.CapturePhotoPreference
import io.github.ismoy.imagepickerkmp.domain.models.CompressionLevel
import io.github.ismoy.imagepickerkmp.domain.models.GalleryPhotoResult
import io.github.ismoy.imagepickerkmp.domain.models.PhotoResult
import io.github.ismoy.imagepickerkmp.presentation.ui.components.GalleryPickerLauncher
import io.github.ismoy.imagepickerkmp.presentation.ui.components.ImagePickerLauncher
import org.jetbrains.compose.resources.stringResource

private val log = Arbor.tag("MediaSelection")

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
    when (mediaSelectionState) {
        MediaSelectionState.Camera ->
            ImagePickerLauncher(
                config = ImagePickerConfig(
                    onPhotoCaptured = { result: PhotoResult ->
                        onPhotoSelected(result.asPhoto())
                        onEndMediaSelection()
                    },
                    onError = {
                        onEndMediaSelection()
                    },
                    onDismiss = {
                        onEndMediaSelection()
                    },
                    cameraCaptureConfig = CameraCaptureConfig(
                        preference = CapturePhotoPreference.QUALITY,
                        compressionLevel = CompressionLevel.LOW,
                        includeExif = false,
                        cropConfig = CropConfig(
                            enabled = false,
                        ),
                    ),
                    enableCrop = false,
                )
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
                    val galleryErrorMessage = stringResource(Res.string.gallery_error_mime_png_only)
                    GalleryPickerLauncher(
                        onPhotosSelected = { photos: List<GalleryPhotoResult> ->
                            photos.firstOrNull()
                                ?.asPhoto()
                                ?.let { photo -> onPhotoSelected(photo) }
                            onEndMediaSelection()
                        },
                        onError = { e ->
                            log.e(e) { "Failed to select photo from gallery." }
                            onEndMediaSelection()
                        },
                        onDismiss = { onEndMediaSelection() },
                        // The device keeps one photo, so the picker offers one.
                        allowMultiple = false,
                        mimeTypeMismatchMessage = galleryErrorMessage
                    )
                }
            )

        MediaSelectionState.NoSelection -> AddDeviceScreenContent(
            photo = photo,
            fallbackPhotoPath = fallbackPhotoPath,
            deviceName = deviceName,
            onDeviceNameChange = onDeviceNameChange,
            onSave = onSave,
            onShowImagePicker = onShowImagePicker,
            onShowGalleryPicker = onShowGalleryPicker,
            addToEnd = {}
        )
    }
}

internal actual val mediaCaptureSupported: Boolean = true
