package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ConfirmDeleteDialog
import com.snapshotpb.configuration.common.composable.DeviceBadge
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.media.MediaSelectionState
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyDeviceConfigure
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfile
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfileAdd
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfiles
import com.snapshotpb.configuration.common.screen.device.MediaSelection
import com.snapshotpb.configuration.common.screen.device.mediaCaptureSupported
import com.snapshotpb.configuration.common.screen.profile.composable.ConnectionCard
import com.snapshotpb.configuration.common.screen.profile.composable.DeviceConfigurationCard
import com.snapshotpb.configuration.common.screen.profile.composable.MarkerProfileCard
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.add_device_screen_gallery_picker
import com.snapshotpb.core.resources.add_device_screen_image_picker
import com.snapshotpb.core.resources.add_icon
import com.snapshotpb.core.resources.button_add_profile
import com.snapshotpb.core.resources.camera_icon
import com.snapshotpb.core.resources.delete_profile_dialog_message
import com.snapshotpb.core.resources.delete_profile_dialog_title
import com.snapshotpb.core.resources.photo_icon
import com.snapshotpb.core.resources.profile_screen_title
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import com.snapshotpb.core.resources.bluetooth_icon
import com.snapshotpb.core.resources.connection_card_failed_hint
import com.snapshotpb.core.resources.device_pending_changes_banner_title
import com.snapshotpb.core.resources.device_pending_changes_banner_message
import com.snapshotpb.core.resources.device_pending_changes_save_to_board
import com.snapshotpb.core.resources.device_pending_changes_discard
import com.snapshotpb.core.resources.device_pending_changes_connect_to_save
import com.snapshotpb.core.resources.device_pending_changes_lockout_to_save
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfilesScreen(
    navigationController: NavigationController,
    deviceId: String,
) {
    val scope = rememberCoroutineScope()
    val profilesScreenViewModel: ProfilesScreenViewModel by rememberViewModel(arg = deviceId)
    var mediaSelectionState by remember {
        mutableStateOf<MediaSelectionState>(MediaSelectionState.NoSelection)
    }
    val onShowImagePicker = { mediaSelectionState = MediaSelectionState.Camera }
    val onShowGalleryPicker = { mediaSelectionState = MediaSelectionState.Gallery }
    var selectedPhoto by remember { mutableStateOf<Photo?>(null) }
    // Deleting a profile discards local configuration with no undo; confirm first.
    var profilePendingDelete: MarkerProfile? by remember { mutableStateOf(null) }

    val device: Device? by profilesScreenViewModel.deviceFlow().collectAsState(null)

    // A connect from here can move the device to its board id (#127). Follow it, or the screen
    // shows a device that the store no longer holds.
    val movedToDeviceId by profilesScreenViewModel.movedToDeviceId.collectAsState(null)
    LaunchedEffect(movedToDeviceId) {
        movedToDeviceId?.let { navigationController.replaceWith(ScreenKeyProfiles(it)) }
    }

    val writeErrors by profilesScreenViewModel.writeErrors.collectAsState()
    val saveFailed = writeErrors.any { it.toString() == deviceId }
    // A write with no result yet disables Save, so a double tap sends one write.
    val writesInFlight by profilesScreenViewModel.writesInFlight.collectAsState()
    val saveInFlight = writesInFlight.any { it.toString() == deviceId }
    // A board in lockout does not accept a change, so Save stays disabled until the user turns
    // off the lockout switch on the board.
    val lockedOut = device?.lockoutEnabled == true

    // The name typed on the picker screen. It is kept here and committed when the picker screen
    // closes (Save, a finished pick or a dismiss). A commit on each keystroke wrote devices.json
    // and ran a Wear sync check for each character.
    var nameDraft by remember { mutableStateOf<String?>(null) }

    fun closeMediaSelection() {
        nameDraft?.let { draft ->
            profilesScreenViewModel.updateDevice(deviceId = deviceId, name = draft, photo = null)
        }
        nameDraft = null
        mediaSelectionState = MediaSelectionState.NoSelection
    }

    // A sync can leave the device with its last profile while the dialog shows. Then the delete is
    // not possible, so the dialog closes.
    val canDeleteProfile = device?.canDeleteProfile() == true
    LaunchedEffect(canDeleteProfile) {
        if (!canDeleteProfile) profilePendingDelete = null
    }

    profilePendingDelete?.takeIf { canDeleteProfile }?.let { pending ->
        ConfirmDeleteDialog(
            title = stringResource(Res.string.delete_profile_dialog_title),
            message = stringResource(Res.string.delete_profile_dialog_message, pending.name),
            onConfirm = {
                profilesScreenViewModel.deleteProfile(pending)
                profilePendingDelete = null
            },
            onDismiss = { profilePendingDelete = null },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (mediaSelectionState != MediaSelectionState.NoSelection) {
            MediaSelection(
                photo = selectedPhoto,
                fallbackPhotoPath = device?.photoPath.orEmpty(),
                deviceName = nameDraft ?: device?.name.orEmpty(),
                // onPhotoSelected below copies the photo into the store once. The name commit
                // never passes the photo again: each pass started one more copy of the same file
                // into the same target, in parallel with the copies before it.
                onDeviceNameChange = { newDeviceName -> nameDraft = newDeviceName },
                onSave = {
                    // closeMediaSelection commits the name. Dismiss the picker in place: a fresh
                    // ScreenKeyProfiles here left a duplicate entry on the back stack, so Back
                    // returned to the same screen.
                    closeMediaSelection()
                },
                mediaSelectionState = mediaSelectionState,
                onPhotoSelected = { photo ->
                    selectedPhoto = photo
                    device?.let { currentDevice ->
                        profilesScreenViewModel.updateDevice(
                            deviceId = deviceId,
                            name = nameDraft ?: currentDevice.name,
                            photo = photo
                        )
                    }
                },
                onEndMediaSelection = { closeMediaSelection() },
                onShowImagePicker = { mediaSelectionState = MediaSelectionState.Camera },
                onShowGalleryPicker = { mediaSelectionState = MediaSelectionState.Gallery }
            )
        } else {
            val isConnected by profilesScreenViewModel.isConnected.collectAsState(false)
            val connectionUiState by profilesScreenViewModel.connectionUiState.collectAsState()
            val connectionProgress by profilesScreenViewModel.connectionProgress.collectAsState()
            val onConnect: () -> Unit = { profilesScreenViewModel.requestConnect() }
            val onDisconnect: () -> Unit = { profilesScreenViewModel.requestDisconnect() }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isTwoPane = fitsTwoPanes(maxWidth, maxHeight)
                if (isTwoPane) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Left Pane: Device Overview & Actions (45% Width)
                        Column(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                                .padding(end = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    DeviceBadge(
                                        photoPath = device?.photoPath.orEmpty(),
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    PhotoCaptureButtons(
                                        buttonSize = 40.dp,
                                        iconSize = 24.dp,
                                        spacing = 8.dp,
                                        onCamera = onShowImagePicker,
                                        onGallery = onShowGalleryPicker,
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }

                            Space(16.dp)

                            Text(
                                text = device?.name.orEmpty().ifEmpty {
                                    stringResource(Res.string.profile_screen_title)
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )

                            Space(16.dp)

                            ConnectionCard(
                                state = connectionUiState,
                                progress = connectionProgress,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                            )

                            DeviceConfigurationCard(
                                onClick = {
                                    navigationController.goTo(ScreenKeyDeviceConfigure(deviceId))
                                }
                            )

                            if (device?.hasDiverged() == true) {
                                Space(16.dp)
                                PendingChangesCard(
                                    isConnected = isConnected,
                                    saveInFlight = saveInFlight,
                                    lockedOut = lockedOut,
                                    saveFailed = saveFailed,
                                    onDiscard = { profilesScreenViewModel.discardPendingChanges(deviceId) },
                                    onSave = {
                                        scope.launch {
                                            profilesScreenViewModel.saveChangesToBoard(deviceId)
                                        }
                                    }
                                )
                            }
                        }

                        // Right Pane: Profile Cards (55% Width)
                        Box(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 80.dp)
                            ) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.profile_screen_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )
                                }

                                itemsIndexed(
                                    device?.knownProfiles ?: emptyList(),
                                    key = { _: Int, profile: MarkerProfile -> profile.id.toString() }
                                ) { index, profile: MarkerProfile ->
                                    if (index > 0) Space(8.dp)

                                    MarkerProfileCard(
                                        index = index,
                                        activeProfile = profile.id == device?.activeProfileId,
                                        profile = profile,
                                        isDiverged = device?.isProfileDiverged(profile.id) ?: false,
                                        deletable = canDeleteProfile,
                                        onProfileSelected = {
                                            navigationController.goTo(
                                                ScreenKeyProfile(
                                                    deviceId = deviceId,
                                                    profileId = profile.id.toString()
                                                )
                                            )
                                        },
                                        onProfileDeleted = { profilePendingDelete = it }
                                    )
                                }
                            }

                            AddProfileFab(
                                device = device,
                                onClick = { navigationController.goTo(ScreenKeyProfileAdd(deviceId)) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                            )
                        }
                    }
                } else {
                    // Mobile Layout (existing code)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = if (device?.hasDiverged() == true) 260.dp else 100.dp
                        )
                    ) {
                        stickyHeader {
                            Surface {
                                Box(
                                    modifier = Modifier.height(260.dp)
                                ) {
                                    DeviceBadge(
                                        photoPath = device?.photoPath.orEmpty(),
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    PhotoCaptureButtons(
                                        buttonSize = 48.dp,
                                        iconSize = 32.dp,
                                        spacing = 16.dp,
                                        onCamera = onShowImagePicker,
                                        onGallery = onShowGalleryPicker,
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomStart)
                                            .background(Color.Black.copy(alpha = 0.3f))
                                    ) {
                                        Text(
                                            text = device?.name.orEmpty().ifEmpty {
                                                stringResource(Res.string.profile_screen_title)
                                            },
                                            style = MaterialTheme.typography.displayMedium,
                                            color = Color.White,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Space(16.dp)
                        }

                        item {
                            ConnectionCard(
                                state = connectionUiState,
                                progress = connectionProgress,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                            )
                        }

                        item {
                            DeviceConfigurationCard(
                                onClick = {
                                    navigationController.goTo(ScreenKeyDeviceConfigure(deviceId))
                                }
                            )
                        }

                        itemsIndexed(
                            device?.knownProfiles ?: emptyList(),
                            key = { _: Int, profile: MarkerProfile -> profile.id.toString() }
                        ) { index, profile: MarkerProfile ->
                            Space()

                            MarkerProfileCard(
                                index = index,
                                activeProfile = profile.id == device?.activeProfileId,
                                profile = profile,
                                isDiverged = device?.isProfileDiverged(profile.id) ?: false,
                                deletable = canDeleteProfile,
                                onProfileSelected = {
                                    navigationController.goTo(
                                        ScreenKeyProfile(
                                            deviceId = deviceId,
                                            profileId = profile.id.toString()
                                        )
                                    )
                                },
                                onProfileDeleted = { profilePendingDelete = it }
                            )
                        }

                        item {
                            Space(16.dp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        AddProfileFab(
                            device = device,
                            onClick = { navigationController.goTo(ScreenKeyProfileAdd(deviceId)) },
                        )

                        if (device?.hasDiverged() == true) {
                            Space(16.dp)
                            PendingChangesCard(
                                isConnected = isConnected,
                                saveInFlight = saveInFlight,
                                lockedOut = lockedOut,
                                saveFailed = saveFailed,
                                onDiscard = { profilesScreenViewModel.discardPendingChanges(deviceId) },
                                onSave = {
                                    scope.launch {
                                        profilesScreenViewModel.saveChangesToBoard(deviceId)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Camera / gallery buttons overlaid on the device badge. Sizes differ between the two layouts, so
 * they are parameters; everything else was a verbatim copy in both branches.
 */
@Composable
private fun PhotoCaptureButtons(
    buttonSize: Dp,
    iconSize: Dp,
    spacing: Dp,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!mediaCaptureSupported) return
    Row(modifier = modifier.padding(8.dp)) {
        FilledIconButton(
            onClick = onCamera,
            modifier = Modifier.size(buttonSize),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Image(
                painter = painterResource(Res.drawable.camera_icon),
                contentDescription = stringResource(Res.string.add_device_screen_image_picker),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.size(iconSize)
            )
        }
        Space(spacing)
        FilledIconButton(
            onClick = onGallery,
            modifier = Modifier.size(buttonSize),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            )
        ) {
            Image(
                painter = painterResource(Res.drawable.photo_icon),
                contentDescription = stringResource(Res.string.add_device_screen_gallery_picker),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondary),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Add-profile FAB, shown only while the device has a free slot.
 *
 * The board reports how many profile slots it supports; [Device.DEFAULT_PROFILE_COUNT] is also the
 * protocol hard cap (`Profiles.profiles max_count:4`), so a board claiming more cannot be honoured.
 * A null device (store not loaded yet) yields 0 and hides the button rather than letting the user
 * add a profile to a device that has not resolved.
 */
@Composable
private fun AddProfileFab(
    device: Device?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profilesCount = device?.knownProfiles?.size ?: 0
    val maxProfiles = (device?.supportedProfileCount ?: 0).coerceAtMost(Device.DEFAULT_PROFILE_COUNT)
    if (profilesCount >= maxProfiles) return

    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(Res.drawable.add_icon),
            contentDescription = stringResource(Res.string.button_add_profile)
        )
    }
}

@Composable
private fun PendingChangesCard(
    isConnected: Boolean,
    saveInFlight: Boolean,
    lockedOut: Boolean,
    saveFailed: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp,
        tonalElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(Res.drawable.bluetooth_icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Space(8.dp)
                Text(
                    text = stringResource(Res.string.device_pending_changes_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Space(8.dp)
            Text(
                text = stringResource(Res.string.device_pending_changes_banner_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Space(12.dp)
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onDiscard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(Res.string.device_pending_changes_discard))
                }
                Space(12.dp)
                Button(
                    onClick = onSave,
                    enabled = isConnected && !saveInFlight && !lockedOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Text(stringResource(Res.string.device_pending_changes_save_to_board))
                }
            }
            if (!isConnected) {
                Space(8.dp)
                Text(
                    text = stringResource(Res.string.device_pending_changes_connect_to_save),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (lockedOut) {
                Space(8.dp)
                Text(
                    text = stringResource(Res.string.device_pending_changes_lockout_to_save),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (saveFailed) {
                Space(8.dp)
                Text(
                    text = stringResource(Res.string.connection_card_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

