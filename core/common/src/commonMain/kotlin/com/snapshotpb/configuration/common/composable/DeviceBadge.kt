package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.default_device_image
import com.snapshotpb.core.resources.profile_badge_description
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

/**
 * The background colour of the default device image. It is the colour of the edge pixels
 * of `default-device-image.png`, so the image shows no seam on this fill.
 * `art/default-device-image/make_default_device_image.py` prints the value, and
 * `DefaultDeviceImageTest` checks that the two agree.
 */
internal val DEFAULT_DEVICE_IMAGE_BACKGROUND = Color(0xFF12171B)

/**
 * The badge photo of a device, or the default image when [photoPath] is empty or the photo
 * does not load.
 *
 * [photoPath] is a value of `Device.photoPath` (a store name) or a URI from the photo picker. The
 * badge gives the image loader [DeviceStore.photoUri], so a store name resolves against the
 * current root of the store. This is the only place in the shared UI that loads a device photo.
 */
@Composable
fun DeviceBadge(
    photoPath: String,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.profile_badge_description)
    // A photo that does not load shows the default image. On the web, the photo is gone
    // when the browser refused its IndexedDB write.
    var photoFailed by remember(photoPath) { mutableStateOf(false) }
    if (photoPath.isNotEmpty() && !photoFailed) {
        val deviceStore: DeviceStore by rememberInstance()
        val photoUri = remember(deviceStore, photoPath) { deviceStore.photoUri(photoPath) }
        AsyncImage(
            model = photoUri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(color = MaterialTheme.colorScheme.onPrimaryContainer),
            onError = { photoFailed = true },
        )
    } else {
        DefaultDeviceImage(
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

/**
 * The default image fits inside the box, with no crop, on a fill of the same colour as its edge.
 * The whole drawing shows in a square box and in a wide box. The box shows no seam in the light
 * theme and in the dark theme.
 */
@Composable
private fun DefaultDeviceImage(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(Res.drawable.default_device_image),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.background(color = DEFAULT_DEVICE_IMAGE_BACKGROUND),
    )
}
