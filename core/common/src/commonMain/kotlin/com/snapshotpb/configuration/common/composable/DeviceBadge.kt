package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_badge_description
import com.snapshotpb.core.resources.shooter_icon
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

/**
 * The badge photo of a device, or the default icon when [photoPath] is empty.
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
    if (photoPath.isNotEmpty()) {
        val deviceStore: DeviceStore by rememberInstance()
        val photoUri = remember(deviceStore, photoPath) { deviceStore.photoUri(photoPath) }
        AsyncImage(
            model = photoUri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            // A photo that does not load shows the default badge. On the web, the photo is gone
            // when the browser refused its IndexedDB write.
            modifier = modifier.background(color = MaterialTheme.colorScheme.onPrimaryContainer),
            error = painterResource(Res.drawable.shooter_icon),
        )
    } else {
        Image(
            painter = painterResource(Res.drawable.shooter_icon),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(color = MaterialTheme.colorScheme.onPrimaryContainer),
        )
    }
}
