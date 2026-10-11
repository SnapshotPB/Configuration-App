package com.snapshotpb.configuration.common.screen.device.composable

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.CharCounter
import com.snapshotpb.configuration.common.composable.DeviceBadge
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.textFieldName
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.model.limitDeviceName
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.add_device_screen_device_name_label
import com.snapshotpb.core.resources.add_device_screen_gallery_picker
import com.snapshotpb.core.resources.add_device_screen_image_config_label
import com.snapshotpb.core.resources.add_device_screen_image_picker
import com.snapshotpb.core.resources.add_device_screen_save_button
import com.snapshotpb.core.resources.camera_icon
import com.snapshotpb.core.resources.photo_icon
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AddDeviceScreenContent(
    photo: Photo?,
    fallbackPhotoPath: String = "",
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onShowImagePicker: () -> Unit,
    onShowGalleryPicker: () -> Unit,
    addToEnd: @Composable () -> Unit,
    // A platform that cannot keep a photo (a web browser without IndexedDB) hides the camera/gallery
    // launchers but still renders the badge, so the controls are not inert. See mediaCaptureSupported.
    showMediaButtons: Boolean = true,
) {
    LazyColumn {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(Res.string.add_device_screen_image_config_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ActionContainer {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) {
                        DeviceBadge(
                            photoPath = photo?.uri ?: fallbackPhotoPath,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (showMediaButtons) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledIconButton(
                                    onClick = onShowImagePicker,
                                    modifier = Modifier.size(64.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Image(
                                        painter = painterResource(Res.drawable.camera_icon),
                                        contentDescription = stringResource(Res.string.add_device_screen_image_picker),
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Space(16.dp)
                                FilledIconButton(
                                    onClick = onShowGalleryPicker,
                                    modifier = Modifier.size(64.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    )
                                ) {
                                    Image(
                                        painter = painterResource(Res.drawable.photo_icon),
                                        contentDescription = stringResource(Res.string.add_device_screen_gallery_picker),
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondary),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Space(8.dp) }

        item {
            val deviceNameLabel = stringResource(Res.string.add_device_screen_device_name_label)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = deviceNameLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 8.dp)
                    .fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { onDeviceNameChange(it.limitDeviceName()) },
                    modifier = Modifier.fillMaxWidth().textFieldName(deviceNameLabel),
                    singleLine = true,
                    placeholder = { Text(deviceNameLabel) },
                    supportingText = {
                        CharCounter(deviceName.length, Device.MAX_NAME_LENGTH)
                    },
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                Button(
                    onClick = onSave,
                    enabled = deviceName.isNotBlank()
                ) {
                    Text(stringResource(Res.string.add_device_screen_save_button))
                }
            }
        }

        item { addToEnd() }
    }
}
