package com.snapshotpb.configuration.common.screen.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.CharCounter
import com.snapshotpb.configuration.common.composable.CollapsingPhotoHeaderColumn
import com.snapshotpb.configuration.common.composable.DeviceBadge
import com.snapshotpb.configuration.common.composable.NumberStepperField
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.UnsavedBadge
import com.snapshotpb.configuration.common.composable.boardLanguageLabel
import com.snapshotpb.configuration.common.composable.boardScreenBrightnessLabel
import com.snapshotpb.configuration.common.composable.divergenceFieldColors
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.composable.gripFrameLabel
import com.snapshotpb.configuration.common.composable.textFieldName
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyFirmwareUpdate
import com.snapshotpb.configuration.common.screen.device.composable.DisplayWindowEditor
import com.snapshotpb.configuration.common.screen.firmware.FirmwareSection
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.SCREEN_OFF_DELAY_MAX
import com.snapshotpb.configuration.model.SCREEN_OFF_DELAY_MIN
import com.snapshotpb.configuration.model.boardValuesMatch
import com.snapshotpb.configuration.model.displayWindowDiverged
import com.snapshotpb.configuration.model.limitBootText
import com.snapshotpb.configuration.model.limitDeviceName
import com.snapshotpb.configuration.model.matchingGripFrame
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_active_profile_details
import com.snapshotpb.core.resources.configure_device_screen_active_profile_empty
import com.snapshotpb.core.resources.configure_device_screen_active_profile_label
import com.snapshotpb.core.resources.configure_device_screen_boot_text_details
import com.snapshotpb.core.resources.configure_device_screen_boot_text_label
import com.snapshotpb.core.resources.configure_device_screen_brightness_details
import com.snapshotpb.core.resources.configure_device_screen_brightness_label
import com.snapshotpb.core.resources.configure_device_screen_brightness_unavailable
import com.snapshotpb.core.resources.configure_device_screen_device_name_label
import com.snapshotpb.core.resources.configure_device_screen_display_window_custom
import com.snapshotpb.core.resources.configure_device_screen_display_window_details
import com.snapshotpb.core.resources.configure_device_screen_display_window_label
import com.snapshotpb.core.resources.configure_device_screen_display_window_unset
import com.snapshotpb.core.resources.configure_device_screen_language_details
import com.snapshotpb.core.resources.configure_device_screen_language_label
import com.snapshotpb.core.resources.configure_device_screen_language_unavailable
import com.snapshotpb.core.resources.configure_device_screen_lockout_details
import com.snapshotpb.core.resources.configure_device_screen_lockout_label
import com.snapshotpb.core.resources.configure_device_screen_lockout_locked
import com.snapshotpb.core.resources.configure_device_screen_lockout_unlocked
import com.snapshotpb.core.resources.configure_device_screen_off_delay_details
import com.snapshotpb.core.resources.configure_device_screen_off_delay_label
import com.snapshotpb.core.resources.configure_device_screen_off_delay_unavailable
import com.snapshotpb.core.resources.configure_device_screen_save_button
import com.snapshotpb.core.resources.configure_device_screen_title
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@Composable
fun ConfigureDeviceScreen(
    navigationController: NavigationController,
    deviceId: String,
) {
    val viewModel: ConfigureDeviceViewModel by rememberViewModel(arg = deviceId)
    val device: Device? by viewModel.deviceFlow().collectAsState(null)

    // The edits live in the view model's draft, not in `remember`, so a rotation keeps them.
    // Both layouts build the same form from that one draft, so a layout change loses nothing.
    val draft = viewModel.draft

    LaunchedEffect(device) {
        viewModel.onDeviceLoaded(device)
    }

    val focusManager = LocalFocusManager.current
    val onSave = {
        // A tap on Save does not take the focus, so the screen-off delay field can still hold a
        // typed draft that it did not commit. Clearing the focus makes the field commit it to the
        // view model draft before clearFocus returns, and saveDraft then reads that draft.
        focusManager.clearFocus()
        viewModel.saveDraft()
        navigationController.navigateBackToProfiles(deviceId)
    }

    // The Firmware section. Its button opens the update screen of this board.
    val firmwareSection: @Composable () -> Unit = {
        FirmwareSection(
            deviceId = deviceId,
            onOpenUpdate = { navigationController.goTo(ScreenKeyFirmwareUpdate(deviceId)) },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTwoPane = fitsTwoPanes(maxWidth, maxHeight)

        if (isTwoPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Left Column: Device badge and title (fixed, non-scrolling)
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(208.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        DeviceBadge(
                            photoPath = device?.photoPath.orEmpty(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Space(16.dp)

                    Text(
                        text = stringResource(Res.string.configure_device_screen_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Right Column: Form fields and Save button (scrollable)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ConfigureDeviceForm(
                        draft = draft,
                        device = device,
                        onEdit = viewModel::editDraft,
                        onSave = onSave,
                        firmwareSection = firmwareSection,
                    )
                }
            }
        } else {
            // Mobile vertical scroll layout: the photo folds away under the scroll, the title
            // bar stays.
            CollapsingPhotoHeaderColumn(
                photoPath = device?.photoPath.orEmpty(),
                title = stringResource(Res.string.configure_device_screen_title),
            ) {
                Space(16.dp)

                ConfigureDeviceForm(
                    draft = draft,
                    device = device,
                    onEdit = viewModel::editDraft,
                    onSave = onSave,
                    firmwareSection = firmwareSection,
                )
            }
        }
    }
}

/**
 * The fields and the Save button of the configuration screen. Both layouts show this one form,
 * so a field is written one time. The two layouts used to hold a copy each.
 *
 * [firmwareSection] is the Firmware section. It is not part of the draft, and Save does not
 * change it.
 *
 * [onEdit] applies a change to the draft. The "unsaved" marks compare the draft with the board
 * state of [device].
 */
@Composable
private fun ColumnScope.ConfigureDeviceForm(
    draft: ConfigureDeviceDraft,
    device: Device?,
    onEdit: ((ConfigureDeviceDraft) -> ConfigureDeviceDraft) -> Unit,
    onSave: () -> Unit,
    firmwareSection: @Composable () -> Unit,
) {
    val boardState = device?.boardState
    val isBootTextDiverged = boardState?.let { draft.bootText != it.bootText } ?: false
    val isActiveProfileDiverged = boardState?.let { draft.activeProfileId != it.activeProfileId } ?: false
    val isLanguageDiverged = boardState?.let { !boardValuesMatch(draft.language, it.language) } ?: false
    val isBrightnessDiverged =
        boardState?.let { !boardValuesMatch(draft.screenBrightness, it.screenBrightness) } ?: false
    val isDelayDiverged =
        boardState?.let { !boardValuesMatch(draft.screenOffDelay, it.screenOffDelay) } ?: false
    val isDisplayWindowDiverged =
        boardState?.let { displayWindowDiverged(draft.displayWindow, it.displayWindow) } ?: false
    val knownProfiles = device?.knownProfiles.orEmpty()

    ActionContainer {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            val deviceNameLabel = stringResource(Res.string.configure_device_screen_device_name_label)
            Text(
                text = deviceNameLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Space(8.dp)
            OutlinedTextField(
                value = draft.deviceName,
                onValueChange = { typed -> onEdit { it.copy(deviceName = typed.limitDeviceName()) } },
                modifier = Modifier.fillMaxWidth().textFieldName(deviceNameLabel),
                singleLine = true,
                supportingText = {
                    CharCounter(draft.deviceName.length, Device.MAX_NAME_LENGTH)
                },
            )
        }
    }

    ActionContainer {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            val bootTextLabel = stringResource(Res.string.configure_device_screen_boot_text_label)
            SectionHeader(
                label = bootTextLabel,
                description = stringResource(Res.string.configure_device_screen_boot_text_details),
                isDiverged = isBootTextDiverged,
            )
            OutlinedTextField(
                value = draft.bootText,
                onValueChange = { typed -> onEdit { it.copy(bootText = typed.limitBootText()) } },
                modifier = Modifier.fillMaxWidth().textFieldName(bootTextLabel),
                singleLine = true,
                supportingText = {
                    CharCounter(draft.bootText.length, Device.MAX_BOOT_TEXT_LENGTH)
                },
                colors = divergenceFieldColors(isBootTextDiverged)
            )
        }
    }

    ActionContainer(
        containerColor = if (isActiveProfileDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SectionHeader(
                label = stringResource(Res.string.configure_device_screen_active_profile_label),
                description = stringResource(Res.string.configure_device_screen_active_profile_details),
                isDiverged = isActiveProfileDiverged,
            )

            if (knownProfiles.isEmpty()) {
                Text(
                    text = stringResource(Res.string.configure_device_screen_active_profile_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                knownProfiles.forEach { profile ->
                    SelectableRow(
                        label = profile.name,
                        selected = profile.id == draft.activeProfileId,
                        onSelect = { onEdit { it.copy(activeProfileId = profile.id) } },
                    )
                }
            }
        }
    }

    BoardLanguageSection(
        selected = draft.language,
        isDiverged = isLanguageDiverged,
        onSelect = { choice -> onEdit { it.copy(language = choice) } },
    )

    ScreenBrightnessSection(
        selected = draft.screenBrightness,
        isDiverged = isBrightnessDiverged,
        onSelect = { choice -> onEdit { it.copy(screenBrightness = choice) } },
    )

    ScreenOffDelaySection(
        value = draft.screenOffDelay,
        isDiverged = isDelayDiverged,
        onValueChange = { delay -> onEdit { it.copy(screenOffDelay = delay) } },
    )

    DisplayWindowSection(
        value = draft.displayWindow,
        isDiverged = isDisplayWindowDiverged,
        customChosen = draft.displayWindowCustom,
        onCustomChosenChange = { custom -> onEdit { it.copy(displayWindowCustom = custom) } },
        onValueChange = { window -> onEdit { it.copy(displayWindow = window) } },
    )

    LockoutStatusRow(enabled = device?.lockoutEnabled == true)

    firmwareSection()

    Space(8.dp)

    Button(
        onClick = onSave,
        enabled = draft.hydrated && draft.deviceName.isNotBlank(),
        modifier = Modifier
            .align(Alignment.End)
            .padding(16.dp),
    ) {
        Text(stringResource(Res.string.configure_device_screen_save_button))
    }

    Space(16.dp)
}

/**
 * Choice of the language the board draws its own menu text in. The board reports its language on
 * each read. Firmware from before the field existed reports none and ignores a choice, so the list
 * shows only when a language is known. The "unsaved" badge marks a choice that differs from the
 * language the board last reported.
 */
@Composable
private fun BoardLanguageSection(
    selected: BoardLanguage?,
    isDiverged: Boolean,
    onSelect: (BoardLanguage) -> Unit,
) {
    ActionContainer(
        containerColor = if (isDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SectionHeader(
                label = stringResource(Res.string.configure_device_screen_language_label),
                description = stringResource(Res.string.configure_device_screen_language_details),
                isDiverged = isDiverged,
            )

            if (selected == null) {
                UnavailableText(stringResource(Res.string.configure_device_screen_language_unavailable))
            } else {
                BoardLanguage.entries.forEach { option ->
                    SelectableRow(
                        label = boardLanguageLabel(option),
                        selected = option == selected,
                        onSelect = { onSelect(option) },
                    )
                }
            }
        }
    }
}

/**
 * Choice of the brightness the board drives its screen at. It is a board setting and not a profile
 * setting, because one screen serves every profile slot. Firmware from before the field reports no
 * brightness and ignores a choice, so the list shows only when a brightness is known.
 */
@Composable
private fun ScreenBrightnessSection(
    selected: BoardScreenBrightness?,
    isDiverged: Boolean,
    onSelect: (BoardScreenBrightness) -> Unit,
) {
    ActionContainer(
        containerColor = if (isDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SectionHeader(
                label = stringResource(Res.string.configure_device_screen_brightness_label),
                description = stringResource(Res.string.configure_device_screen_brightness_details),
                isDiverged = isDiverged,
            )

            if (selected == null) {
                UnavailableText(stringResource(Res.string.configure_device_screen_brightness_unavailable))
            } else {
                BoardScreenBrightness.entries.forEach { option ->
                    SelectableRow(
                        label = boardScreenBrightnessLabel(option),
                        selected = option == selected,
                        onSelect = { onSelect(option) },
                    )
                }
            }
        }
    }
}

/**
 * The seconds the board leaves its screen on after the last button press.
 *
 * The range is 1 to 999 seconds and there is no value that keeps the screen on for ever. `0` is
 * reserved on the wire for "leave the board's delay alone", so the field never offers it. Firmware
 * from before the field reports no delay, and the field shows only when a delay is known.
 */
@Composable
private fun ScreenOffDelaySection(
    value: Int?,
    isDiverged: Boolean,
    onValueChange: (Int) -> Unit,
) {
    ActionContainer(
        containerColor = if (isDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SectionHeader(
                label = stringResource(Res.string.configure_device_screen_off_delay_label),
                description = stringResource(Res.string.configure_device_screen_off_delay_details),
                isDiverged = isDiverged,
            )

            if (value == null) {
                UnavailableText(stringResource(Res.string.configure_device_screen_off_delay_unavailable))
            } else {
                // The text keeps a draft, so the user can clear the field or type a 0 on the way
                // to 10. Only a value in range commits at once, and focus loss commits the rest.
                NumberStepperField(
                    value = value,
                    onValueChange = onValueChange,
                    format = { stored -> stored.toString() },
                    parse = { text -> text.trim().toIntOrNull() },
                    normalize = { typed -> typed.coerceIn(SCREEN_OFF_DELAY_MIN, SCREEN_OFF_DELAY_MAX) },
                    step = { current, delta ->
                        (current + delta).coerceIn(SCREEN_OFF_DELAY_MIN, SCREEN_OFF_DELAY_MAX)
                    },
                    isDiverged = isDiverged,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

/**
 * The part of the board's panel that the marker's grip frame reveals.
 *
 * The list offers a rectangle for each measured grip frame ([GripFrame.offered]), and a custom
 * entry that unlocks the drawing for a frame the app has no measured rectangle for.
 * [GripFrame.FULL_PANEL] is always first: a legal rectangle in the wrong place shows the user
 * nothing at all, so there is always one tap back to the whole panel. The board offers the same
 * way back under `Setup > Full window`.
 *
 * The editor always shows, unlike the language and brightness sections above. Those settings exist
 * on every board that knows the field, but a board that has never been given a window reports none,
 * and the user must still be able to set one. So the drawing starts at the whole panel while
 * [value] is `null`, no entry is marked, and a save leaves the board's own window alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayWindowSection(
    value: DisplayWindow?,
    isDiverged: Boolean,
    customChosen: Boolean,
    onCustomChosenChange: (Boolean) -> Unit,
    onValueChange: (DisplayWindow) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // A custom rectangle that happens to equal a known frame must keep the custom entry selected,
    // so the choice is carried in [customChosen] and not derived from the rectangle alone.
    val matchedFrame = value?.matchingGripFrame()
    val isCustom = customChosen || (value != null && matchedFrame == null)
    val shownWindow = value ?: DisplayWindow.FULL_PANEL
    val customLabel = stringResource(Res.string.configure_device_screen_display_window_custom)
    val selectedLabel = when {
        isCustom -> customLabel
        matchedFrame != null -> gripFrameLabel(matchedFrame)
        else -> stringResource(Res.string.configure_device_screen_display_window_unset)
    }

    ActionContainer(
        containerColor = if (isDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SectionHeader(
                label = stringResource(Res.string.configure_device_screen_display_window_label),
                description = stringResource(Res.string.configure_device_screen_display_window_details),
                isDiverged = isDiverged,
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = divergenceFieldColors(isDiverged),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    GripFrame.offered.forEach { frame ->
                        DropdownMenuItem(
                            text = { Text(gripFrameLabel(frame)) },
                            onClick = {
                                onCustomChosenChange(false)
                                onValueChange(frame.window)
                                expanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(customLabel) },
                        onClick = {
                            onCustomChosenChange(true)
                            onValueChange(shownWindow)
                            expanded = false
                        },
                    )
                }
            }

            Space(12.dp)

            DisplayWindowEditor(
                window = value,
                enabled = isCustom,
                onWindowChange = onValueChange,
            )
        }
    }
}

/** Title and description for a settings section, with the "unsaved" badge beside the title. */
@Composable
private fun SectionHeader(label: String, description: String, isDiverged: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isDiverged) {
            Space(8.dp)
            UnsavedBadge()
        }
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Space(8.dp)
}

/** Why a board setting has no control: the board reported no value for it. */
@Composable
private fun UnavailableText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * Read-only indicator of the board's firing lockout. Lockout is a board-owned fact — there is no
 * app-writable lockout field in the protocol — so this is display-only (no toggle): the value
 * tracks the latest board read. A locked board is highlighted in the error color because it means
 * the marker will not fire.
 */
@Composable
private fun LockoutStatusRow(enabled: Boolean) {
    ActionContainer {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                text = stringResource(Res.string.configure_device_screen_lockout_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.configure_device_screen_lockout_details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Space(8.dp)
            Text(
                text = stringResource(
                    if (enabled) {
                        Res.string.configure_device_screen_lockout_locked
                    } else {
                        Res.string.configure_device_screen_lockout_unlocked
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** A radio-button row for a single choice from a list (active profile, board language). */
@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}