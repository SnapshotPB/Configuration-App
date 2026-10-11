package com.snapshotpb.configuration.common.util

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Formats to exactly [numOfDecimals] decimal places, rounding half away from zero.
 *
 * Kotlin/Common has no `String.format`, so this is hand-rolled. It rounds the *scaled integer*
 * rather than reassembling `whole + fraction` as a `Double`: the old approach rounded negatives
 * toward zero (`-3.25` rendered as `"-3.2"`) and dropped the sign entirely for values in `(-1, 0)`.
 * Non-finite inputs fall back to [Double.toString] rather than producing nonsense digits.
 */
fun Double.format(numOfDecimals: Int): String {
    if (isNaN() || isInfinite()) return toString()
    if (numOfDecimals <= 0) return roundHalfAwayFromZero(this).toString()

    val scale = pow10(numOfDecimals)
    val scaled = roundHalfAwayFromZero(this * scale)
    val sign = if (scaled < 0L) "-" else ""
    val magnitude = abs(scaled)
    val whole = magnitude / scale
    val fraction = magnitude % scale
    return "$sign$whole.${fraction.toString().padStart(numOfDecimals, '0')}"
}

/**
 * Parses a user-entered one-decimal string into the tenths integer the board protocol stores, or
 * `null` when the text is not a number.
 *
 * The board holds several fields as tenths of a unit: every timing and trigger field is tenths of
 * a millisecond, and the fire-rate cap is tenths of a ball per second. The helper is unit-neutral
 * and serves them all.
 *
 * **Rounds, never truncates.** `value * 10` is inexact in IEEE-754 — `2.9 * 10` is
 * `28.999999999999996` and `0.3 * 10` is `2.9999999999999996` — so a `toInt()` here silently
 * stored one tenth *less* than the user typed for roughly half of all one-decimal inputs, across
 * every field in the profile editor.
 */
fun parseTenths(text: String): Int? {
    val value = text.trim().toDoubleOrNull() ?: return null
    val tenths = value * 10
    if (tenths.isNaN()) return null
    return tenths.roundToInt()
}

/** Renders a tenths value as the one-decimal string [parseTenths] accepts. */
fun formatTenths(tenths: Int): String = (tenths / 10.0).format(1)

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10L }
    return result
}

/** [Double.roundToLong] rounds half *up* (`-2.5` to `-2`); display convention here is half away from zero. */
private fun roundHalfAwayFromZero(value: Double): Long =
    if (value < 0.0) -((-value).roundToLong()) else value.roundToLong()
