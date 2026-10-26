package com.snapshotpb.configuration.common.media

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.Photo

/**
 * Camera and gallery picker launchers for device photos on non-web targets.
 *
 * The `imagepickerkmp` implementation lives in `:core:camera` so its Android CameraX native
 * libraries and manifest permissions only reach the apps that capture device photos
 * (`:app:android`, `:app:desktop`, `:app:ios`), not `:app:wear` or `:app:browser`.
 */
interface ImagePicker {
    @Composable
    fun CameraLauncher(
        onPhotoSelected: (Photo) -> Unit,
        onEndMediaSelection: () -> Unit,
    )

    @Composable
    fun GalleryLauncher(
        onPhotoSelected: (Photo) -> Unit,
        onEndMediaSelection: () -> Unit,
    )
}
