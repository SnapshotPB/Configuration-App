package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.CharCounter
import com.snapshotpb.configuration.common.composable.CollapsingPhotoHeaderColumn
import com.snapshotpb.configuration.common.composable.NumberStepperField
import com.snapshotpb.configuration.common.composable.ProfileTimingDiagram
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.UnsavedBadge
import com.snapshotpb.configuration.common.composable.divergenceFieldColors
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.composable.markerEyeModeLabel
import com.snapshotpb.configuration.common.composable.markerFireModeLabel
import com.snapshotpb.configuration.common.composable.markerProfileFieldLabel
import com.snapshotpb.configuration.common.composable.markerProfileFieldUnitSymbol
import com.snapshotpb.configuration.common.composable.textFieldName
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.MarkerProfileGroup
import com.snapshotpb.configuration.model.limitProfileName
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_save_button
import com.snapshotpb.core.resources.connecting_screen_back_button
import com.snapshotpb.core.resources.new_device_screen_cancel_button
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_delay_details
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_dwell_closed_details
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_dwell_open_details
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_timeout_details
import com.snapshotpb.core.resources.profile_label_eye_debounce_details
import com.snapshotpb.core.resources.profile_label_eye_mode
import com.snapshotpb.core.resources.profile_label_eye_mode_details
import com.snapshotpb.core.resources.profile_label_eye_threshold_details
import com.snapshotpb.core.resources.profile_label_fire_mode
import com.snapshotpb.core.resources.profile_label_fire_mode_details
import com.snapshotpb.core.resources.profile_label_fire_rate_cap_details
import com.snapshotpb.core.resources.profile_label_name
import com.snapshotpb.core.resources.profile_label_name_details
import com.snapshotpb.core.resources.profile_label_ramping_custom_multiplier_details
import com.snapshotpb.core.resources.profile_label_ramping_custom_rof_minimum_details
import com.snapshotpb.core.resources.profile_label_ramping_custom_trigger_pulls_details
import com.snapshotpb.core.resources.profile_label_sear_solenoid_dwell_details
import com.snapshotpb.core.resources.profile_label_trigger_debounce_off_details
import com.snapshotpb.core.resources.profile_label_trigger_debounce_on_details
import com.snapshotpb.core.resources.profile_loading
import com.snapshotpb.core.resources.profile_mode_unknown_details
import com.snapshotpb.core.resources.profile_not_found
import com.snapshotpb.core.resources.profile_screen_details_title
import com.snapshotpb.core.resources.profile_section_basic_function
import com.snapshotpb.core.resources.profile_section_eye
import com.snapshotpb.core.resources.profile_section_ramping
import com.snapshotpb.core.resources.profile_section_timing
import com.snapshotpb.core.resources.profile_section_timing_overview
import com.snapshotpb.core.resources.profile_section_timing_overview_description
import com.snapshotpb.core.resources.profile_section_trigger
import com.snapshotpb.core.resources.profile_settings_title
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navigationController: NavigationController,
    deviceId: String,
    profileId: String,
) {
    val viewModel: ProfileViewModel by rememberViewModel(
        arg = ProfileViewModel.Args(
            deviceId,
            profileId
        )
    )
    // In-progress edits live in the view model's draft, not in `remember`. A rotation recreates
    // the Android activity, and `remember` state goes with it. The view model belongs to the nav
    // entry, so the draft survives a rotation and goes when the user leaves the screen. It is not
    // kept across process death: then the draft reloads the saved profile from the store, which
    // stays the one source of truth for saved profiles.
    //
    // The seed observes [ProfileViewModel.profileLoad] rather than reading the store once, because
    // DeviceStore loads from disk asynchronously and its StateFlow answers `emptyList()` until that
    // lands. A one-shot read therefore stranded this screen on "Loading profile…" forever whenever
    // it composed first — routinely, on saved-state restore. Seeding only on the first `Found` keeps
    // later store writes from clobbering in-progress edits.
    val profile: MarkerProfile? = viewModel.draft
    var boardProfile: MarkerProfile? by remember { mutableStateOf(null) }
    val photoPath: String by viewModel.devicePhotoPath().collectAsState("")
    val profileLoad: ProfileLoad by viewModel.profileLoad().collectAsState(ProfileLoad.Loading)
    val focusManager = LocalFocusManager.current

    LaunchedEffect(profileLoad) {
        val found = profileLoad as? ProfileLoad.Found ?: return@LaunchedEffect
        viewModel.seedDraft(found.profile)
        // The baseline is never edited on this screen, so it always follows the store. A write
        // result or a conflict resolution that lands while the screen is open moves the "unsaved"
        // badges with it instead of comparing the draft against a stale baseline.
        boardProfile = found.boardProfile
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTwoPane = fitsTwoPanes(maxWidth, maxHeight)

        val currentProfile = profile
        if (currentProfile == null) {
            // Distinguish "still loading" from "the store is loaded and this profile is gone" —
            // the latter otherwise reads as a hang.
            val message = if (profileLoad is ProfileLoad.Missing) {
                stringResource(Res.string.profile_not_found)
            } else {
                stringResource(Res.string.profile_loading)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = message, style = MaterialTheme.typography.bodyLarge)
                    if (profileLoad is ProfileLoad.Missing) {
                        Space(16.dp)
                        Button(onClick = { navigationController.goBack() }) {
                            Text(stringResource(Res.string.connecting_screen_back_button))
                        }
                    }
                }
            }
        } else {
            // Both orientations render the SAME form; only the arrangement differs. Landscape puts
            // the timing overview in a fixed left pane and the controls in a scrollable right pane;
            // portrait stacks a photo header above one scrolling column with the overview inline.
            //
            // These two branches used to be ~300 lines each of field-for-field copies. Every field
            // therefore had to be edited twice, and the copies had already drifted apart in ways
            // nobody noticed. Extracting the sections leaves the branches as pure layout.
            val onProfileChange: (MarkerProfile) -> Unit = { viewModel.editDraft(it) }
            val onCancel = { navigationController.goBack() }
            val onSave = {
                // A tap on Save does not take the focus, so a number field can still hold a typed
                // draft that it did not commit (an out-of-range value). Clear the focus first: the
                // field then commits its draft in its focus callback, before clearFocus returns.
                // Then read the draft again, because currentProfile is the copy from before that
                // commit.
                focusManager.clearFocus()
                viewModel.updateProfile(viewModel.draft ?: currentProfile)
                navigationController.goBack()
            }

            if (isTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stringResource(Res.string.profile_screen_details_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Space(16.dp)

                        TimingOverviewSection(currentProfile)
                    }

                    // Right Pane: Form Controls (Scrollable)
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                            .padding(start = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stringResource(Res.string.profile_settings_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        BasicFunctionSection(
                            currentProfile = currentProfile,
                            boardProfile = boardProfile,
                            onProfileChange = onProfileChange,
                        )

                        ConfigurationSpace()

                        EyeSection(
                            currentProfile = currentProfile,
                            boardProfile = boardProfile,
                            onProfileChange = onProfileChange,
                        )

                        ConfigurationSpace()

                        FieldGroupSection(
                            title = stringResource(Res.string.profile_section_timing),
                            group = MarkerProfileGroup.TIMING,
                            currentProfile = currentProfile,
                            boardProfile = boardProfile,
                            onProfileChange = onProfileChange,
                        )

                        ConfigurationSpace()

                        FieldGroupSection(
                            title = stringResource(Res.string.profile_section_trigger),
                            group = MarkerProfileGroup.TRIGGER,
                            currentProfile = currentProfile,
                            boardProfile = boardProfile,
                            onProfileChange = onProfileChange,
                        )

                        RampingSection(
                            currentProfile = currentProfile,
                            boardProfile = boardProfile,
                            onProfileChange = onProfileChange,
                        )

                        Space(16.dp)

                        ProfileActions(onCancel = onCancel, onSave = onSave)

                        Space(32.dp)
                    }
                }
            } else {
                // Mobile vertical scroll layout: the photo folds away under the scroll, the
                // title bar stays.
                CollapsingPhotoHeaderColumn(
                    photoPath = photoPath,
                    title = stringResource(Res.string.profile_screen_details_title),
                ) {
                    Space(16.dp)

                    BasicFunctionSection(
                        currentProfile = currentProfile,
                        boardProfile = boardProfile,
                        onProfileChange = onProfileChange,
                    )

                    ConfigurationSpace()

                    EyeSection(
                        currentProfile = currentProfile,
                        boardProfile = boardProfile,
                        onProfileChange = onProfileChange,
                    )

                    ConfigurationSpace()

                    TimingOverviewSection(currentProfile)

                    ConfigurationSpace()

                    FieldGroupSection(
                        title = stringResource(Res.string.profile_section_timing),
                        group = MarkerProfileGroup.TIMING,
                        currentProfile = currentProfile,
                        boardProfile = boardProfile,
                        onProfileChange = onProfileChange,
                    )

                    ConfigurationSpace()

                    FieldGroupSection(
                        title = stringResource(Res.string.profile_section_trigger),
                        group = MarkerProfileGroup.TRIGGER,
                        currentProfile = currentProfile,
                        boardProfile = boardProfile,
                        onProfileChange = onProfileChange,
                    )

                    RampingSection(
                        currentProfile = currentProfile,
                        boardProfile = boardProfile,
                        onProfileChange = onProfileChange,
                    )

                    Space(16.dp)

                    ProfileActions(onCancel = onCancel, onSave = onSave)

                    Space(32.dp)
                }
            }
        }
    }
}

/**
 * Read-only visualisation of the firing cycle. Shown in both orientations — landscape gives it its
 * own pane, portrait places it inline between the eye and timing sections.
 */
@Composable
private fun TimingOverviewSection(currentProfile: MarkerProfile) {
    ConfigurationSection(stringResource(Res.string.profile_section_timing_overview)) {
        Column {
            Text(
                text = stringResource(Res.string.profile_section_timing_overview_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Space(8.dp)
            ProfileTimingDiagram(profile = currentProfile)
        }
    }
}

/**
 * Name, fire mode and the numeric fields of the basic group (the fire-rate cap). The table does
 * not show the cap in the NXL 2026 fire mode, because the league sets the cap of that mode.
 */
@Composable
private fun BasicFunctionSection(
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
) {
    val isNameDiverged = boardProfile?.let { currentProfile.name != it.name } ?: false
    val isFireModeDiverged = boardProfile?.let { currentProfile.fireMode != it.fireMode } ?: false

    ConfigurationSection(stringResource(Res.string.profile_section_basic_function)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val nameLabel = stringResource(Res.string.profile_label_name)
            FieldHeader(
                label = nameLabel,
                description = stringResource(Res.string.profile_label_name_details),
                isDiverged = isNameDiverged,
            )
            OutlinedTextField(
                value = currentProfile.name,
                onValueChange = { newName ->
                    onProfileChange(currentProfile.copy(name = newName.limitProfileName()))
                },
                singleLine = true,
                supportingText = {
                    CharCounter(currentProfile.name.length, Device.MAX_PROFILE_NAME_LENGTH)
                },
                colors = divergenceFieldColors(isNameDiverged),
                modifier = Modifier.fillMaxWidth().textFieldName(nameLabel)
            )
        }

        ConfigurationSpace()

        EnumField(
            label = stringResource(Res.string.profile_label_fire_mode),
            description = stringResource(Res.string.profile_label_fire_mode_details),
            value = currentProfile.fireMode,
            entries = MarkerFireMode.selectable,
            isDiverged = isFireModeDiverged,
            onValueChange = { newMode -> onProfileChange(currentProfile.copy(fireMode = newMode)) },
            entryLabel = { entry -> markerFireModeLabel(entry) }
        )

        GroupFields(
            group = MarkerProfileGroup.BASIC,
            currentProfile = currentProfile,
            boardProfile = boardProfile,
            onProfileChange = onProfileChange,
            spaceBeforeFirst = true,
        )
    }
}

/** Eye mode plus the numeric fields of the eye group, which the table shows only with an eye. */
@Composable
private fun EyeSection(
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
) {
    val isEyeModeDiverged = boardProfile?.let { currentProfile.eyeMode != it.eyeMode } ?: false

    ConfigurationSection(stringResource(Res.string.profile_section_eye)) {
        EnumField(
            label = stringResource(Res.string.profile_label_eye_mode),
            description = stringResource(Res.string.profile_label_eye_mode_details),
            value = currentProfile.eyeMode,
            entries = MarkerEyeMode.selectable,
            isDiverged = isEyeModeDiverged,
            onValueChange = { newMode -> onProfileChange(currentProfile.copy(eyeMode = newMode)) },
            entryLabel = { entry -> markerEyeModeLabel(entry) }
        )

        GroupFields(
            group = MarkerProfileGroup.EYE,
            currentProfile = currentProfile,
            boardProfile = boardProfile,
            onProfileChange = onProfileChange,
            spaceBeforeFirst = true,
        )
    }
}

/**
 * Ramping parameters. Renders nothing when the table shows no ramping field for the profile, which
 * is each fire mode other than the custom ramping mode.
 */
@Composable
private fun RampingSection(
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
) {
    if (fieldsOf(MarkerProfileGroup.RAMPING).none { it.isShownFor(currentProfile) }) return

    ConfigurationSpace()

    FieldGroupSection(
        title = stringResource(Res.string.profile_section_ramping),
        group = MarkerProfileGroup.RAMPING,
        currentProfile = currentProfile,
        boardProfile = boardProfile,
        onProfileChange = onProfileChange,
    )
}

/** A section that holds only the numeric fields of one [group]. */
@Composable
private fun FieldGroupSection(
    title: String,
    group: MarkerProfileGroup,
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
) {
    ConfigurationSection(title) {
        GroupFields(
            group = group,
            currentProfile = currentProfile,
            boardProfile = boardProfile,
            onProfileChange = onProfileChange,
            spaceBeforeFirst = false,
        )
    }
}

/**
 * The numeric fields of [group] that the table shows for [currentProfile], in table order, with
 * a space between them. [spaceBeforeFirst] also puts a space above the first field, for a
 * section that has other controls above it.
 */
@Composable
private fun GroupFields(
    group: MarkerProfileGroup,
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
    spaceBeforeFirst: Boolean,
) {
    fieldsOf(group)
        .filter { it.isShownFor(currentProfile) }
        .forEachIndexed { index, field ->
            // Keyed on the field, so each text draft stays with its own field when a field
            // above it shows or hides.
            key(field) {
                if (index > 0 || spaceBeforeFirst) ConfigurationSpace()
                ProfileNumberField(
                    field = field,
                    currentProfile = currentProfile,
                    boardProfile = boardProfile,
                    onProfileChange = onProfileChange,
                )
            }
        }
}

/** The fields of [group], in table order. */
private fun fieldsOf(group: MarkerProfileGroup): List<MarkerProfileField> =
    MarkerProfileField.entries.filter { it.group == group }

/**
 * One numeric profile field. The limits, the step of the buttons and the unit come from
 * [MarkerProfileField], so the phone editor and the watch steppers visit the same values. The
 * text field keeps a draft and commits only a value the board keeps (see [NumberStepperField]
 * and [typedValue]).
 */
@Composable
private fun ProfileNumberField(
    field: MarkerProfileField,
    currentProfile: MarkerProfile,
    boardProfile: MarkerProfile?,
    onProfileChange: (MarkerProfile) -> Unit,
) {
    val value = field.get(currentProfile)
    val isDiverged = boardProfile?.let { field.get(it) != value } ?: false

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        FieldHeader(
            label = markerProfileFieldLabel(field),
            description = markerProfileFieldDescription(field),
            isDiverged = isDiverged,
        )
        NumberStepperField(
            value = value,
            onValueChange = { newValue -> onProfileChange(field.set(currentProfile, newValue)) },
            format = field::formatInput,
            parse = field::parseInput,
            normalize = field::typedValue,
            step = field::step,
            isDiverged = isDiverged,
            keyboardType = if (field.isTenths) KeyboardType.Decimal else KeyboardType.Number,
            unit = markerProfileFieldUnitSymbol(field),
        )
    }
}

/** The localized description under the label of a numeric profile field. */
@Composable
private fun markerProfileFieldDescription(field: MarkerProfileField): String = stringResource(
    when (field) {
        MarkerProfileField.FIRE_RATE_CAP -> Res.string.profile_label_fire_rate_cap_details
        MarkerProfileField.EYE_THRESHOLD -> Res.string.profile_label_eye_threshold_details
        MarkerProfileField.EYE_DEBOUNCE -> Res.string.profile_label_eye_debounce_details
        MarkerProfileField.COCKING_SOLENOID_TIMEOUT ->
            Res.string.profile_label_cocking_solenoid_timeout_details
        MarkerProfileField.SEAR_SOLENOID_DWELL -> Res.string.profile_label_sear_solenoid_dwell_details
        MarkerProfileField.COCKING_SOLENOID_DELAY -> Res.string.profile_label_cocking_solenoid_delay_details
        MarkerProfileField.COCKING_SOLENOID_DWELL_OPEN ->
            Res.string.profile_label_cocking_solenoid_dwell_open_details
        MarkerProfileField.COCKING_SOLENOID_DWELL_CLOSED ->
            Res.string.profile_label_cocking_solenoid_dwell_closed_details
        MarkerProfileField.TRIGGER_DEBOUNCE_ON -> Res.string.profile_label_trigger_debounce_on_details
        MarkerProfileField.TRIGGER_DEBOUNCE_OFF -> Res.string.profile_label_trigger_debounce_off_details
        MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER ->
            Res.string.profile_label_ramping_custom_multiplier_details
        MarkerProfileField.RAMPING_CUSTOM_TRIGGER_PULLS ->
            Res.string.profile_label_ramping_custom_trigger_pulls_details
        MarkerProfileField.RAMPING_CUSTOM_ROF_MINIMUM ->
            Res.string.profile_label_ramping_custom_rof_minimum_details
    }
)

/** Label, "unsaved" badge and description above a field. */
@Composable
private fun FieldHeader(label: String, description: String, isDiverged: Boolean) {
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
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Space(8.dp)
}

/** Cancel / Save row. Edits live in the screen's draft until Save writes them to the store. */
@Composable
private fun ProfileActions(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Button(onClick = onCancel) {
            Text(stringResource(Res.string.new_device_screen_cancel_button))
        }
        Space(16.dp)
        Button(onClick = onSave) {
            Text(stringResource(Res.string.configure_device_screen_save_button))
        }
    }
}

@Composable
private fun ConfigurationSection(
    title: String,
    content: @Composable () -> Unit,
) {
    ActionContainer(
        modifier = Modifier.padding(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Space(16.dp)

        content()
    }
}

@Composable
private fun ConfigurationSpace() = Space(8.dp)

/**
 * A choice of one value from [entries]. [value] can be a value that [entries] does not hold: a
 * mode that the board reports and this app does not know. Then the field shows a note under the
 * value until the user selects an entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> EnumField(
    label: String,
    description: String,
    value: T,
    entries: List<T>,
    isDiverged: Boolean = false,
    onValueChange: (T) -> Unit,
    entryLabel: @Composable (T) -> String = { it.name },
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        FieldHeader(label = label, description = description, isDiverged = isDiverged)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = entryLabel(value),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                supportingText = if (value !in entries) {
                    { Text(stringResource(Res.string.profile_mode_unknown_details)) }
                } else {
                    null
                },
                colors = divergenceFieldColors(isDiverged),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entryLabel(entry)) },
                        onClick = {
                            onValueChange(entry)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
