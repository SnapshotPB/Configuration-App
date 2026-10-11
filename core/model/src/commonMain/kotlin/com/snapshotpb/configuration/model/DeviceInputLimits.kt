package com.snapshotpb.configuration.model

/*
 * Firmware-enforced numeric ranges and the app's concrete defaults for the tunable
 * [MarkerProfile] fields, in one place so the UI caps, the domain defaults, and
 * [MarkerProfile.clampedToFirmwareRanges] never drift apart.
 *
 * The firmware silently *clamps* every config value at its single write chokepoint — an
 * out-of-range value is bounded into range, never rejected, and the write still succeeds. Several
 * fields additionally treat `0` as "select the firmware default" rather than a literal `0`. If the
 * app ever sent such a value the board would store a different one, and the app's optimistic
 * baseline would disagree with it forever (a permanent "unsaved"/conflict loop). So the app mirrors
 * the firmware exactly and, for the "`0` ⇒ default" fields, ships a concrete nonzero default and
 * blocks `0` in the UI so the sentinel is never sent. `fire_rate_cap` is the one exception: there
 * `0` = *uncapped* is a real value that round-trips, so it stays enterable.
 *
 * `fire_rate_cap` counts TENTHS of a ball per second, the same fixed-point convention the timing
 * fields use for milliseconds. The tag carried whole balls per second until the NXL 2026 ramping
 * cap of 10.5 BPS showed that a whole number cannot express a legal rate.
 */
/** Ceiling for tenths-of-a-millisecond timing fields (0–9999.9 ms). */
const val TENTHS_MS_MAX = 99999

/** Ceiling for the ramping count fields (trigger pulls, ROF minimum). */
const val COUNT_MAX = 9999

/** Eye threshold is a percent (0–100) of ADC full scale. */
const val EYE_THRESHOLD_MAX = 100

/**
 * When capped, `fire_rate_cap` clamps to 10–1000 tenths of a ball per second (1.0–100.0 BPS);
 * `0` (uncapped) is left untouched.
 */
const val RATE_CAP_MIN = 10
const val RATE_CAP_MAX = 1000

/** Concrete default eye threshold; also what the firmware substitutes for a `0` send. */
const val EYE_THRESHOLD_DEFAULT = 50

/** Concrete default trigger debounce (= 2.0 ms). The firmware's `0 ⇒ 200` (20 ms) is a fail-safe. */
const val TRIGGER_DEBOUNCE_DEFAULT = 20

/** Concrete default cocking-solenoid anti-jam timeout (= 500 ms); the firmware's `0 ⇒ 5000`. */
const val COCKING_TIMEOUT_DEFAULT = 5000

/**
 * Default eye debounce (= 2.0 ms). The firmware uses this value in place of a `0` send, because a
 * `0` debounce would latch a seated ball off one sensor sample.
 */
const val EYE_DEBOUNCE_DEFAULT = 20

/**
 * The rate cap of the NXL 2026 fire mode, in tenths of a ball per second: 105 = 10.5 BPS, which is
 * one ball in 95 ms. The league sets this limit, and the user cannot adjust it. In that mode the
 * board uses this cap and does not read `fire_rate_cap` (see [MarkerFireMode.hasFixedRateCap]).
 */
const val NXL_2026_RATE_CAP = 105

/**
 * Factory-fresh fire-rate cap, in tenths of a ball per second: the NXL 2026 ramping limit. Must be
 * materialized explicitly (proto3 `0` means uncapped, not the default).
 */
const val FIRE_RATE_CAP_DEFAULT = NXL_2026_RATE_CAP

/**
 * The screen-off delay clamps to 1-999 seconds. On the wire `0` means "leave the board's delay
 * alone", so the UI never offers `0` and the domain model carries `null` for "not reported".
 */
const val SCREEN_OFF_DELAY_MIN = 1
const val SCREEN_OFF_DELAY_MAX = 999

/** Concrete default screen-off delay; also what the board materializes for a stored `0`. */
const val SCREEN_OFF_DELAY_DEFAULT = 5

/**
 * Bounds a screen-off delay to the firmware's range, or reports none.
 *
 * The board clamps 1000 or more to 999 and still applies the rest of the message, so the app
 * clamps first and caches a baseline the board really stored. A value below the minimum is no legal
 * delay at all, so it becomes "not reported" rather than 1 second: the wire reserves `0` for "leave
 * the board's delay alone". One helper so the write boundary and the protocol mapper cannot drift.
 */
fun Int?.clampedScreenOffDelay(): Int? =
    this?.takeIf { it >= SCREEN_OFF_DELAY_MIN }?.coerceAtMost(SCREEN_OFF_DELAY_MAX)

/**
 * Trims a user-entered device name to [Device.MAX_NAME_LENGTH] characters.
 *
 * The device name is stored locally only (it is not part of the board protocol), so no
 * character filtering is applied — only a length cap.
 */
fun String.limitDeviceName(): String = take(Device.MAX_NAME_LENGTH)

/**
 * Gives the name that the app stores for a new device.
 *
 * The board advertises "Snapshot PB", which is longer than [Device.MAX_NAME_LENGTH]. The
 * configuration screen cuts a stored name to that limit, so a Save would rename the device. An
 * advertised name that does not fit therefore gives [defaultName], which is also cut to the limit.
 */
fun newDeviceName(advertisedName: String, defaultName: String): String =
    advertisedName.takeIf { it.isNotBlank() && it.length <= Device.MAX_NAME_LENGTH }
        ?: defaultName.limitDeviceName()

/**
 * Trims a profile name to [Device.MAX_PROFILE_NAME_LENGTH] characters, keeping only ASCII
 * (code point 0-127) characters.
 *
 * The board stores the profile name in a fixed-width byte buffer (nanopb `char[11]`, so at most 10
 * payload bytes). Restricting to single-byte ASCII keeps the character count and the byte limit in
 * agreement — 10 characters is always exactly 10 bytes — so the on-write byte truncation
 * (`takeUtf8Bytes` in the OTS mapper) never silently drops typed characters and the value
 * round-trips instead of perpetually diverging from the board's stored 10-byte name.
 */
fun String.limitProfileName(): String =
    filter { it.code <= 127 }.take(Device.MAX_PROFILE_NAME_LENGTH)

/**
 * Trims boot text to [Device.MAX_BOOT_TEXT_LENGTH] characters, keeping only ASCII
 * (code point 0-127) characters.
 *
 * The board stores boot text in a 10-byte field, so restricting input to single-byte ASCII
 * keeps the character count and the byte limit in agreement: 10 characters is always exactly
 * 10 bytes. This guarantees the on-write byte truncation can never silently drop characters the
 * user has already typed, and keeps the on-screen character counter accurate.
 */
fun String.limitBootText(): String =
    filter { it.code <= 127 }.take(Device.MAX_BOOT_TEXT_LENGTH)
