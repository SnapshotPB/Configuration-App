package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.CharCounter
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.CollapsingPhotoHeaderColumn
import com.snapshotpb.configuration.common.composable.textFieldName
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfile
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.limitProfileName
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_add_screen_create_button
import com.snapshotpb.core.resources.profile_add_screen_title
import com.snapshotpb.core.resources.profile_label_name
import com.snapshotpb.core.resources.profile_label_name_details
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@Composable
fun ProfileAddScreen(
    navigationController: NavigationController,
    deviceId: String,
) {
    val profileAddViewModel: ProfileAddViewModel by rememberViewModel(arg = deviceId)
    // Observed, not read once: DeviceStore loads from disk asynchronously, so a one-shot read here
    // saw an empty store and threw from inside this effect — an unrecoverable crash on Android
    // saved-state restore. The Create button gates on a resolved device instead.
    val device: Device? by profileAddViewModel.deviceFlow().collectAsState(null)
    var profileName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // The same folding photo header as the other portrait editors. The form is short, so the
    // photo normally stays open.
    CollapsingPhotoHeaderColumn(
        photoPath = device?.photoPath.orEmpty(),
        title = stringResource(Res.string.profile_add_screen_title),
    ) {
        Space(16.dp)

        ActionContainer(modifier = Modifier.padding(8.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                val nameLabel = stringResource(Res.string.profile_label_name)
                Text(
                    text = nameLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.profile_label_name_details),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Space(8.dp)
                OutlinedTextField(
                    value = profileName,
                    // ASCII-only, capped at Device.MAX_PROFILE_NAME_LENGTH so the value never
                    // exceeds the 10-byte wire cap (nanopb Profile.name char[11] reserves the
                    // NUL); shares the same helper the profile editor and the write clamp use,
                    // so it can never perpetually diverge from the board's stored name.
                    onValueChange = { profileName = it.limitProfileName() },
                    singleLine = true,
                    supportingText = {
                        CharCounter(profileName.length, Device.MAX_PROFILE_NAME_LENGTH)
                    },
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .textFieldName(nameLabel),
                )
                Space(8.dp)
                Button(
                    onClick = {
                        // Null when the device vanished from the store while this screen was
                        // open — never navigate to a profile that was not created.
                        val profileId = profileAddViewModel.addProfile(profileName)
                            ?: return@Button navigationController.goBack()
                        navigationController.replaceWith(
                            ScreenKeyProfile(deviceId = deviceId, profileId = profileId)
                        )
                    },
                    // Also gated on a resolved device: until the store load lands there is
                    // nothing to append the profile to.
                    enabled = profileName.isNotBlank() && device != null,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(Res.string.profile_add_screen_create_button))
                }
            }
        }

        Space(32.dp)
    }
}
