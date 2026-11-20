package com.snapshotpb.configuration.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.snapshotpb.configuration.common.media.ImagePicker
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.gallery_error_mime_png_only
import com.toxicbakery.logging.Arbor
import io.github.ismoy.imagepickerkmp.config.CameraCaptureConfig
import io.github.ismoy.imagepickerkmp.config.CropConfig
import io.github.ismoy.imagepickerkmp.picker.CompressionLevel
import io.github.ismoy.imagepickerkmp.picker.ImagePickerKMPConfig
import io.github.ismoy.imagepickerkmp.picker.ImagePickerResult
import io.github.ismoy.imagepickerkmp.picker.rememberImagePickerKMP
import org.jetbrains.compose.resources.stringResource

private val log = Arbor.tag("MediaSelection")

/**
 * Each launcher starts the picker one time when it enters the composition.
 * The launcher calls `onEndMediaSelection` one time when the picker stops.
 */
internal class CameraImagePicker : ImagePicker {
    @Composable
    override fun CameraLauncher(
        onPhotoSelected: (Photo) -> Unit,
        onEndMediaSelection: () -> Unit,
    ) {
        val state = rememberImagePickerKMP(
            config = ImagePickerKMPConfig(
                cameraCaptureConfig = CameraCaptureConfig(
                    compressionLevel = CompressionLevel.LOW,
                    includeExif = false,
                    cropConfig = CropConfig(
                        enabled = false,
                    ),
                ),
            ),
        )
        LaunchedEffect(state) {
            state.launchCamera()
        }
        PickerResultEffect(
            result = state.result,
            onPhotoSelected = onPhotoSelected,
            onEndMediaSelection = onEndMediaSelection,
        )
    }

    @Composable
    override fun GalleryLauncher(
        onPhotoSelected: (Photo) -> Unit,
        onEndMediaSelection: () -> Unit,
    ) {
        val galleryErrorMessage = stringResource(Res.string.gallery_error_mime_png_only)
        val state = rememberImagePickerKMP()
        LaunchedEffect(state) {
            state.launchGallery(
                // The device keeps one photo, so the picker offers one.
                allowMultiple = false,
                mimeTypeMismatchMessage = galleryErrorMessage,
            )
        }
        PickerResultEffect(
            result = state.result,
            onPhotoSelected = onPhotoSelected,
            onEndMediaSelection = onEndMediaSelection,
        )
    }
}

@Composable
private fun PickerResultEffect(
    result: ImagePickerResult,
    onPhotoSelected: (Photo) -> Unit,
    onEndMediaSelection: () -> Unit,
) {
    val currentOnPhotoSelected by rememberUpdatedState(onPhotoSelected)
    val currentOnEndMediaSelection by rememberUpdatedState(onEndMediaSelection)
    LaunchedEffect(result) {
        when (result) {
            is ImagePickerResult.Success -> {
                result.first
                    ?.asPhoto()
                    ?.let { photo -> currentOnPhotoSelected(photo) }
                currentOnEndMediaSelection()
            }

            ImagePickerResult.Dismissed -> currentOnEndMediaSelection()

            is ImagePickerResult.Error -> {
                log.e(result.exception) { "The image picker stopped with an error." }
                currentOnEndMediaSelection()
            }

            ImagePickerResult.Idle,
            ImagePickerResult.Loading,
            -> Unit
        }
    }
}
