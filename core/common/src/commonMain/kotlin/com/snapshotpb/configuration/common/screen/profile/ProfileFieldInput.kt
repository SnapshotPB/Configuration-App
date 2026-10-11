package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.common.util.formatTenths
import com.snapshotpb.configuration.common.util.parseTenths
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.MarkerProfileUnit
import com.snapshotpb.configuration.model.RATE_CAP_MIN

// Text input rules of the phone editor for the numeric profile fields. The limits, the steps and
// the visibility come from MarkerProfileField, the table the watch apps use too, so a change
// there reaches every editor.

/** Whether the field stores tenths of a unit, which the editor shows with one decimal. */
internal val MarkerProfileField.isTenths: Boolean
    get() = when (unit) {
        MarkerProfileUnit.TENTHS_MS,
        MarkerProfileUnit.TENTHS_BPS,
        MarkerProfileUnit.TENTHS_MULTIPLE -> true
        MarkerProfileUnit.PERCENT,
        MarkerProfileUnit.BPS,
        MarkerProfileUnit.COUNT -> false
    }

/** The text the editor shows for a stored [value] of this field. */
internal fun MarkerProfileField.formatInput(value: Int): String =
    if (isTenths) formatTenths(value) else value.toString()

/** The stored value of typed [text], or `null` when the text is not a number. */
internal fun MarkerProfileField.parseInput(text: String): Int? =
    if (isTenths) parseTenths(text) else text.trim().toIntOrNull()

/**
 * The value the board keeps for a typed [value]: the value held inside [MarkerProfileField.min]
 * and [MarkerProfileField.max]. The firmware raises a fire-rate cap of 1 to 9 tenths (0.1 to
 * 0.9 BPS) to [RATE_CAP_MIN], so a typed value in that gap rises here as well. The field then
 * shows the value the board will hold, and the clamp at the write boundary changes nothing.
 */
internal fun MarkerProfileField.typedValue(value: Int): Int {
    val bounded = value.coerceIn(min, max)
    return if (this == MarkerProfileField.FIRE_RATE_CAP && bounded in 1 until RATE_CAP_MIN) {
        RATE_CAP_MIN
    } else {
        bounded
    }
}
