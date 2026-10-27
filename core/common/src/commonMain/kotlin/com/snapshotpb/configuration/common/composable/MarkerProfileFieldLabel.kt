package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.common.util.formatTenths
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.MarkerProfileUnit
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_delay
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_dwell_closed
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_dwell_open
import com.snapshotpb.core.resources.profile_label_cocking_solenoid_timeout
import com.snapshotpb.core.resources.profile_label_eye_debounce
import com.snapshotpb.core.resources.profile_label_eye_threshold
import com.snapshotpb.core.resources.profile_label_fire_rate_cap
import com.snapshotpb.core.resources.profile_label_ramping_custom_multiplier
import com.snapshotpb.core.resources.profile_label_ramping_custom_rof_minimum
import com.snapshotpb.core.resources.profile_label_ramping_custom_trigger_pulls
import com.snapshotpb.core.resources.profile_label_sear_solenoid_delay
import com.snapshotpb.core.resources.profile_label_sear_solenoid_dwell
import com.snapshotpb.core.resources.profile_label_trigger_debounce_off
import com.snapshotpb.core.resources.profile_label_trigger_debounce_on
import com.snapshotpb.core.resources.profile_unit_bps
import com.snapshotpb.core.resources.profile_unit_ms
import com.snapshotpb.core.resources.profile_unit_multiple
import com.snapshotpb.core.resources.profile_unit_percent
import com.snapshotpb.core.resources.profile_value_bps
import com.snapshotpb.core.resources.profile_value_ms
import com.snapshotpb.core.resources.profile_value_multiple
import com.snapshotpb.core.resources.profile_value_percent
import com.snapshotpb.core.resources.profile_value_uncapped
import org.jetbrains.compose.resources.stringResource

/**
 * The localized label of a [MarkerProfileField], the same `profile_label_*` string the phone
 * editor shows above its text field.
 */
@Composable
fun markerProfileFieldLabel(field: MarkerProfileField): String = stringResource(
    when (field) {
        MarkerProfileField.FIRE_RATE_CAP -> Res.string.profile_label_fire_rate_cap
        MarkerProfileField.EYE_THRESHOLD -> Res.string.profile_label_eye_threshold
        MarkerProfileField.EYE_DEBOUNCE -> Res.string.profile_label_eye_debounce
        MarkerProfileField.COCKING_SOLENOID_TIMEOUT -> Res.string.profile_label_cocking_solenoid_timeout
        MarkerProfileField.SEAR_SOLENOID_DWELL -> Res.string.profile_label_sear_solenoid_dwell
        MarkerProfileField.SEAR_SOLENOID_DELAY -> Res.string.profile_label_sear_solenoid_delay
        MarkerProfileField.COCKING_SOLENOID_DELAY -> Res.string.profile_label_cocking_solenoid_delay
        MarkerProfileField.COCKING_SOLENOID_DWELL_OPEN -> Res.string.profile_label_cocking_solenoid_dwell_open
        MarkerProfileField.COCKING_SOLENOID_DWELL_CLOSED -> Res.string.profile_label_cocking_solenoid_dwell_closed
        MarkerProfileField.TRIGGER_DEBOUNCE_ON -> Res.string.profile_label_trigger_debounce_on
        MarkerProfileField.TRIGGER_DEBOUNCE_OFF -> Res.string.profile_label_trigger_debounce_off
        MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER -> Res.string.profile_label_ramping_custom_multiplier
        MarkerProfileField.RAMPING_CUSTOM_TRIGGER_PULLS -> Res.string.profile_label_ramping_custom_trigger_pulls
        MarkerProfileField.RAMPING_CUSTOM_ROF_MINIMUM -> Res.string.profile_label_ramping_custom_rof_minimum
    }
)

/**
 * The localized display of a stored [value] of [field]: milliseconds with one decimal, a percent,
 * balls per second with one decimal or as a whole number, a multiple with one decimal, a plain
 * count, or the word for uncapped when the cap is `0`.
 */
@Composable
fun markerProfileFieldValueLabel(field: MarkerProfileField, value: Int): String = when (field.unit) {
    MarkerProfileUnit.TENTHS_MS -> stringResource(Res.string.profile_value_ms, formatTenths(value))
    MarkerProfileUnit.PERCENT -> stringResource(Res.string.profile_value_percent, value)
    MarkerProfileUnit.TENTHS_BPS ->
        if (value == 0) {
            stringResource(Res.string.profile_value_uncapped)
        } else {
            stringResource(Res.string.profile_value_bps, formatTenths(value))
        }
    MarkerProfileUnit.BPS -> stringResource(Res.string.profile_value_bps, value.toString())
    MarkerProfileUnit.TENTHS_MULTIPLE -> stringResource(Res.string.profile_value_multiple, formatTenths(value))
    MarkerProfileUnit.COUNT -> value.toString()
}

/**
 * The localized unit symbol that an editor shows after the number of [field], or `null` for a
 * plain count, which has no unit.
 */
@Composable
fun markerProfileFieldUnitSymbol(field: MarkerProfileField): String? = when (field.unit) {
    MarkerProfileUnit.TENTHS_MS -> stringResource(Res.string.profile_unit_ms)
    MarkerProfileUnit.PERCENT -> stringResource(Res.string.profile_unit_percent)
    MarkerProfileUnit.TENTHS_BPS, MarkerProfileUnit.BPS -> stringResource(Res.string.profile_unit_bps)
    MarkerProfileUnit.TENTHS_MULTIPLE -> stringResource(Res.string.profile_unit_multiple)
    MarkerProfileUnit.COUNT -> null
}
