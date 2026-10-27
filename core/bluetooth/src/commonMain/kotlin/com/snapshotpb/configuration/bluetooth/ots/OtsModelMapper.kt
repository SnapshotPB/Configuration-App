package com.snapshotpb.configuration.bluetooth.ots

import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardModel
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.clampedScreenOffDelay
import snapshotpb.autococker.v1.AutocockerConfig
import snapshotpb.autococker.v1.AutocockerFireMode
import snapshotpb.common.v1.EyeMode
import snapshotpb.v1.BoltMode
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.DisplayWindow
import snapshotpb.v1.Language
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import snapshotpb.v1.ScreenBrightness
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

/**
 * Returns the current local date using kotlinx-datetime.
 */
fun getCurrentLocalDate(): LocalDate {
    return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
}

/**
 * Firmware string fields are nanopb `char[N]` buffers, which reserve one byte for the NUL
 * terminator: the wire payload must be at most `N - 1` bytes or the whole message fails to decode
 * ("string overflow"). These caps are `N - 1` for the deployed firmware buffers (finding #8).
 */
// firmware Profile.name char[11] → 10 payload bytes; ASCII-limited names keep 10 chars == 10 bytes,
// so this byte cap agrees with the char cap in core:model (the canonical home for the length).
private const val PROFILE_NAME_MAX_BYTES = com.snapshotpb.configuration.model.Device.MAX_PROFILE_NAME_LENGTH

// firmware device_config.boot_text char[11] → 10 payload bytes; ASCII-limited boot text keeps
// 10 chars == 10 bytes. Sourced from core:model like the profile-name cap above rather than being
// a second literal that can drift away from the UI's character counter.
private const val BOOT_TEXT_MAX_BYTES = com.snapshotpb.configuration.model.Device.MAX_BOOT_TEXT_LENGTH

/**
 * The board's read-only `device` object carried a `device_id` that was not the required 16-byte
 * UUID (e.g. the proto3 empty-bytes default or a short send). Surfaced as a typed [OtsException] on
 * the OTS read path instead of an unchecked [IllegalArgumentException] from [Uuid.fromByteArray]
 * (finding #48).
 */
class OtsInvalidDeviceId(actualSize: Int) :
    OtsException("device_id must be 16 bytes but was $actualSize")

/**
 * Truncates the string to at most [maxBytes] UTF-8 bytes without splitting a
 * code point. The firmware fields (boot text, profile name) are fixed-width
 * byte buffers, so truncation must count encoded bytes — not UTF-16 chars — to
 * stay consistent with the firmware and the Zepp client.
 */
internal fun String.takeUtf8Bytes(maxBytes: Int): String {
    if (encodeToByteArray().size <= maxBytes) return this
    val result = StringBuilder()
    var byteCount = 0
    var i = 0
    while (i < length) {
        val ch = this[i]
        // Keep a surrogate pair together so a supplementary code point is never split.
        val unit = if (ch.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            substring(i, i + 2)
        } else {
            ch.toString()
        }
        val unitBytes = unit.encodeToByteArray().size
        if (byteCount + unitBytes > maxBytes) break
        result.append(unit)
        byteCount += unitBytes
        i += unit.length
    }
    return result.toString()
}

/**
 * Derives a deterministic, stable profile UUID from a device UUID and its slot index.
 *
 * The board's profiles have no id of their own, so one is synthesised by XOR-ing the slot into the
 * device UUID's last byte. Two consequences are deliberate and must not be "fixed" casually:
 *
 * - Slot 0's profile UUID equals the device UUID. Harmless today (profile and device ids are never
 *   looked up in the same map) but do not merge those namespaces.
 * - The result is not a well-formed RFC 4122 UUID — the variant/version bits are whatever the XOR
 *   leaves. It is an opaque stable key, not something to hand to an RFC-conformant parser.
 *
 * Changing this derivation would orphan every profile id already persisted in `devices.json` and on
 * synced watches, so it needs a migration, not an edit.
 */
fun getProfileUuid(deviceUuid: Uuid, slotIndex: Int): Uuid {
    val bytes = deviceUuid.toByteArray().copyOf()
    bytes[15] = (bytes[15].toInt() xor slotIndex).toByte()
    return Uuid.fromByteArray(bytes)
}

/**
 * Maps protocol AutocockerFireMode to domain MarkerFireMode. The two enums have the same entry
 * names. An unset (`UNSPECIFIED`) value maps to `UNKNOWN`. The wire copy reads a number that this
 * app does not know as `UNSPECIFIED` too (see [AutocockerConfigWire.toGenerated]), so it also shows
 * as unknown.
 */
fun AutocockerFireMode.toDomain(): MarkerFireMode = when (this) {
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_MECHANICAL -> MarkerFireMode.MECHANICAL
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI -> MarkerFireMode.SEMI
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY -> MarkerFireMode.TRIGGER_ONLY
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_FULL_AUTO -> MarkerFireMode.FULL_AUTO
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026 -> MarkerFireMode.RAMPING_NXL_2026
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING -> MarkerFireMode.CUSTOM_RAMPING
    AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED -> MarkerFireMode.UNKNOWN
}

/**
 * Maps domain MarkerFireMode to protocol AutocockerFireMode. `UNKNOWN` maps to `UNSPECIFIED`.
 * The profile write replaces it with the board's own number when it has one (see
 * [withBoardOwnedFieldsFrom]).
 */
fun MarkerFireMode.toProtocol(): AutocockerFireMode = when (this) {
    MarkerFireMode.MECHANICAL -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_MECHANICAL
    MarkerFireMode.SEMI -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI
    MarkerFireMode.TRIGGER_ONLY -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY
    MarkerFireMode.FULL_AUTO -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_FULL_AUTO
    MarkerFireMode.RAMPING_NXL_2026 -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026
    MarkerFireMode.CUSTOM_RAMPING -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING
    MarkerFireMode.UNKNOWN -> AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED
}

/**
 * Maps protocol [BoltMode] to domain [MarkerBoltMode]. An unset (`UNSPECIFIED`) value maps to
 * `null`, so the board has no bolt mode to show. Firmware from before the field existed omits
 * it, and [decodeDeviceConfig] reads a number that this app does not know as `UNSPECIFIED`.
 */
fun BoltMode.toDomain(): MarkerBoltMode? = when (this) {
    BoltMode.BOLT_MODE_CLOSED -> MarkerBoltMode.CLOSED
    BoltMode.BOLT_MODE_OPEN -> MarkerBoltMode.OPEN
    BoltMode.BOLT_MODE_UNSPECIFIED -> null
}

/**
 * Maps domain [MarkerBoltMode] to protocol [BoltMode].
 */
fun MarkerBoltMode.toProtocol(): BoltMode = when (this) {
    MarkerBoltMode.CLOSED -> BoltMode.BOLT_MODE_CLOSED
    MarkerBoltMode.OPEN -> BoltMode.BOLT_MODE_OPEN
}

/**
 * Maps protocol EyeMode to domain MarkerEyeMode. An unset (`UNSPECIFIED`) value, and a number that
 * this app does not know, map to `UNKNOWN`.
 */
fun EyeMode.toDomain(): MarkerEyeMode = when (this) {
    EyeMode.EYE_MODE_OFF -> MarkerEyeMode.OFF
    EyeMode.EYE_MODE_REFLECTIVE -> MarkerEyeMode.REFLECTIVE
    EyeMode.EYE_MODE_BREAK_BEAM -> MarkerEyeMode.BREAK_BEAM
    EyeMode.EYE_MODE_UNSPECIFIED -> MarkerEyeMode.UNKNOWN
}

/**
 * Maps domain MarkerEyeMode to protocol EyeMode. `UNKNOWN` maps to `UNSPECIFIED`, which the
 * profile write replaces with the board's own number when it has one.
 */
fun MarkerEyeMode.toProtocol(): EyeMode = when (this) {
    MarkerEyeMode.OFF -> EyeMode.EYE_MODE_OFF
    MarkerEyeMode.REFLECTIVE -> EyeMode.EYE_MODE_REFLECTIVE
    MarkerEyeMode.BREAK_BEAM -> EyeMode.EYE_MODE_BREAK_BEAM
    MarkerEyeMode.UNKNOWN -> EyeMode.EYE_MODE_UNSPECIFIED
}

/**
 * Maps protocol BoardModel to domain BoardModel. An unset (`UNSPECIFIED`) value maps to `UNKNOWN`.
 */
fun snapshotpb.v1.BoardModel.toDomain(): BoardModel = when (this) {
    snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER -> BoardModel.AUTOCOCKER
    snapshotpb.v1.BoardModel.BOARD_MODEL_UNSPECIFIED -> BoardModel.UNKNOWN
}

/**
 * Maps protocol Language to domain BoardLanguage. An unset (`UNSPECIFIED`) value maps to `null`, so
 * the board has no language to show. Firmware from before the field existed omits it, and
 * [decodeDeviceConfig] reads a number that this app does not know as `UNSPECIFIED`.
 */
fun Language.toDomain(): BoardLanguage? = when (this) {
    Language.LANGUAGE_EN -> BoardLanguage.EN
    Language.LANGUAGE_ES -> BoardLanguage.ES
    Language.LANGUAGE_DE -> BoardLanguage.DE
    Language.LANGUAGE_FR -> BoardLanguage.FR
    Language.LANGUAGE_UNSPECIFIED -> null
}

/**
 * Maps domain BoardLanguage to protocol Language.
 */
fun BoardLanguage.toProtocol(): Language = when (this) {
    BoardLanguage.EN -> Language.LANGUAGE_EN
    BoardLanguage.ES -> Language.LANGUAGE_ES
    BoardLanguage.DE -> Language.LANGUAGE_DE
    BoardLanguage.FR -> Language.LANGUAGE_FR
}

/**
 * Maps protocol ScreenBrightness to domain BoardScreenBrightness. An unset (`UNSPECIFIED`) value
 * maps to `null`, so the board has no brightness to show. Firmware from before the field existed
 * omits it, and [decodeDeviceConfig] reads a number that this app does not know as `UNSPECIFIED`.
 */
fun ScreenBrightness.toDomain(): BoardScreenBrightness? = when (this) {
    ScreenBrightness.SCREEN_BRIGHTNESS_LOW -> BoardScreenBrightness.LOW
    ScreenBrightness.SCREEN_BRIGHTNESS_DEFAULT -> BoardScreenBrightness.DEFAULT
    ScreenBrightness.SCREEN_BRIGHTNESS_HIGH -> BoardScreenBrightness.HIGH
    ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED -> null
}

/**
 * Maps domain BoardScreenBrightness to protocol ScreenBrightness.
 */
fun BoardScreenBrightness.toProtocol(): ScreenBrightness = when (this) {
    BoardScreenBrightness.LOW -> ScreenBrightness.SCREEN_BRIGHTNESS_LOW
    BoardScreenBrightness.DEFAULT -> ScreenBrightness.SCREEN_BRIGHTNESS_DEFAULT
    BoardScreenBrightness.HIGH -> ScreenBrightness.SCREEN_BRIGHTNESS_HIGH
}

/**
 * Maps the protocol screen-off delay to the domain value. On the wire `0` means that the board
 * reported no delay, so it maps to `null` rather than to a literal zero.
 */
fun UInt.screenOffDelayToDomain(): Int? = toInt().takeIf { it > 0 }

/**
 * Maps the protocol DisplayWindow to the domain rectangle.
 *
 * A rectangle outside the board's own bounds maps to `null`, which reads as "the board reported no
 * window". The board enforces those bounds on every write, so this cannot arise from a board that
 * stored one; it guards a malformed read. `null` is the right answer rather than a clamp, because a
 * clamped value would claim the board holds a rectangle it does not, and the divergence check could
 * never clear.
 */
fun DisplayWindow.toDomain(): com.snapshotpb.configuration.model.DisplayWindow? =
    com.snapshotpb.configuration.model.DisplayWindow(
        x = x.toInt(),
        y = y.toInt(),
        w = w.toInt(),
        h = h.toInt(),
    ).takeIf { it.isWithinPanel() }

/**
 * Maps the domain rectangle to the protocol DisplayWindow.
 *
 * It clamps first. The board rejects a whole message that carries an out-of-bounds rectangle, and
 * the wire type is unsigned, so a negative value would otherwise reach the board as a huge one.
 */
fun com.snapshotpb.configuration.model.DisplayWindow.toProtocol(): DisplayWindow =
    clampedToPanel().let { safe ->
        DisplayWindow(
            x = safe.x.toUInt(),
            y = safe.y.toUInt(),
            w = safe.w.toUInt(),
            h = safe.h.toUInt(),
        )
    }

/**
 * Converts a protocol Profile message to a domain MarkerProfile.
 */
fun Profile.toDomain(deviceUuid: Uuid, slotIndex: Int): MarkerProfile {
    // Autococker-specific firing config lives in the `board_config` oneof. Fall back to
    // protocol defaults when a board reports no autococker arm (e.g. a future board model).
    val autococker = autococker ?: AutocockerConfig()
    return MarkerProfile(
        id = getProfileUuid(deviceUuid, slotIndex),
        name = name,
        fireMode = autococker.fireMode.toDomain(),
        fireRateCap = fireRateCap.toInt(),
        eyeMode = autococker.eyeMode.toDomain(),
        eyeThreshold = autococker.eyeThreshold.toInt(),
        eyeDebounce = autococker.eyeDebounce.toInt(),
        searSolenoidDwell = autococker.searSolenoidDwell.toInt(),
        searSolenoidDelay = autococker.searSolenoidDelay.toInt(),
        cockingSolenoidDelay = autococker.cockingSolenoidDelay.toInt(),
        cockingSolenoidDwellOpen = autococker.cockingSolenoidDwellOpen.toInt(),
        cockingSolenoidDwellClosed = autococker.cockingSolenoidDwellClosed.toInt(),
        rampingCustomMultiplier = autococker.rampingCustomMultiplier.toInt(),
        rampingCustomTriggerPulls = autococker.rampingCustomTriggerPulls.toInt(),
        rampingCustomROFMinimum = autococker.rampingCustomRofMinimum.toInt(),
        triggerDebounceOn = autococker.triggerDebounceOn.toInt(),
        triggerDebounceOff = autococker.triggerDebounceOff.toInt(),
        cockingSolenoidTimeout = autococker.cockingSolenoidTimeout.toInt(),
        sourceSlot = slotIndex,
    )
}

/**
 * Converts a domain MarkerProfile to a protocol Profile message. Firing mechanics are
 * carried in the `autococker` arm of the `board_config` oneof.
 *
 * Each numeric field goes out in each fire mode. The NXL 2026 mode does not read `fire_rate_cap`,
 * only the custom ramping mode reads the `ramping_custom_*` fields, open-bolt mode ignores
 * `cocking_solenoid_delay`, and closed-bolt mode / `MECHANICAL` / `TRIGGER_ONLY` ignore
 * `sear_solenoid_delay`, but the board keeps the stored values and uses them again after a change
 * of mode. A write that sent a default in their place would lose the values that the editors hide.
 */
fun MarkerProfile.toProtocol(): Profile {
    return Profile(
        // Byte-aware cap so the wire value never overflows the firmware char[11] buffer (#8) and so
        // it agrees with the divergence check instead of perpetually diverging on multi-byte names
        // (#46).
        name = name.takeUtf8Bytes(PROFILE_NAME_MAX_BYTES),
        // Floor every numeric at 0 before the unsigned wire encoding: a negative Int would wrap to
        // a huge UInt instead of being caught. Callers normally clamp first
        // (MarkerProfile.clampedToFirmwareRanges), but this is the last-line safety net at every
        // .toUInt() site, guarding legacy/import/watch-sync values that skip the clamp.
        fireRateCap = fireRateCap.coerceAtLeast(0).toUInt(),
        autococker = AutocockerConfig(
            fireMode = fireMode.toProtocol(),
            eyeMode = eyeMode.toProtocol(),
            eyeThreshold = eyeThreshold.coerceAtLeast(0).toUInt(),
            eyeDebounce = eyeDebounce.coerceAtLeast(0).toUInt(),
            searSolenoidDwell = searSolenoidDwell.coerceAtLeast(0).toUInt(),
            cockingSolenoidDelay = cockingSolenoidDelay.coerceAtLeast(0).toUInt(),
            cockingSolenoidDwellOpen = cockingSolenoidDwellOpen.coerceAtLeast(0).toUInt(),
            cockingSolenoidDwellClosed = cockingSolenoidDwellClosed.coerceAtLeast(0).toUInt(),
            rampingCustomMultiplier = rampingCustomMultiplier.coerceAtLeast(0).toUInt(),
            rampingCustomTriggerPulls = rampingCustomTriggerPulls.coerceAtLeast(0).toUInt(),
            rampingCustomRofMinimum = rampingCustomROFMinimum.coerceAtLeast(0).toUInt(),
            triggerDebounceOn = triggerDebounceOn.coerceAtLeast(0).toUInt(),
            triggerDebounceOff = triggerDebounceOff.coerceAtLeast(0).toUInt(),
            cockingSolenoidTimeout = cockingSolenoidTimeout.coerceAtLeast(0).toUInt(),
            searSolenoidDelay = searSolenoidDelay.coerceAtLeast(0).toUInt(),
        )
    )
}

/**
 * Converts the board's read-only Device + DeviceConfig + Profiles messages to a domain Device.
 *
 * `device_id`, `lockoutEnabled`, `model` and `supportedProfileCount` come from the read-only
 * [protoDevice]; the writable `activeProfile`, `bootText`, `language`, `screenBrightness` and
 * `screenOffDelay` come from [protoDeviceConfig].
 */
fun toDomainDevice(
    protoDevice: snapshotpb.v1.Device,
    protoDeviceConfig: DeviceConfig,
    protoProfiles: Profiles,
    deviceName: String,
    lastSeenDate: LocalDate,
): com.snapshotpb.configuration.model.Device {
    // The board's own device_id is the identity of the device, the same on every phone and every
    // platform. The scan key of the peripheral is not: it is different on each phone (#127).
    val idBytes = protoDevice.deviceId
    // Uuid.fromByteArray requires exactly 16 bytes; the proto3 default is empty. Fail with a
    // typed OtsException rather than an unchecked IllegalArgumentException (finding #48).
    if (idBytes.size != 16) throw OtsInvalidDeviceId(idBytes.size)
    val deviceUuid = Uuid.fromByteArray(idBytes)

    // SLOT SEMANTICS ARE POSITIONAL, AND MUST ROUND-TRIP.
    //
    // The domain list's index *is* the board slot: [toProtocolProfiles] writes `knownProfiles[i]`
    // back to slot i, [toProtocolDeviceConfig] encodes `active_profile` as an index into the same
    // list, and [getProfileUuid] derives each profile's id from that index. A null slot silently
    // dropped mid-list (the old `mapIndexedNotNull`) broke all three at once: every later profile
    // shifted down one slot on the next write, changing which profile the board treats as active
    // and mis-pairing the carried-forward board-owned fields in [toProtocolProfiles], leaving a
    // divergence the user cannot clear. Take the dense leading run instead, so the domain list is
    // always a prefix of the board's slots and read and write agree by construction.
    val mappedProfiles = protoProfiles.profiles
        .takeWhile { it != null }
        .filterNotNull()
        .mapIndexed { index, profile -> profile.toDomain(deviceUuid, index) }

    // active_profile is a 1-based index into those slots (0 = unset). Out-of-range — including a
    // slot past the dense prefix — must not resolve to a bogus UUID (finding #60).
    val activeProfileUuid = protoDeviceConfig.activeProfileSlot(mappedProfiles.size)
        ?.let { slot -> getProfileUuid(deviceUuid, slot) }

    val bootText = protoDeviceConfig.bootTextToDomain()

    return com.snapshotpb.configuration.model.Device(
        id = deviceUuid,
        name = deviceName,
        lastSeen = lastSeenDate,
        knownProfiles = mappedProfiles,
        photoPath = "",
        bootText = bootText,
        activeProfileId = activeProfileUuid,
        lockoutEnabled = protoDevice.lockoutEnabled,
        language = protoDeviceConfig.language.toDomain(),
        screenBrightness = protoDeviceConfig.screenBrightness.toDomain(),
        screenOffDelay = protoDeviceConfig.screenOffDelay.screenOffDelayToDomain(),
        displayWindow = protoDeviceConfig.displayWindow?.toDomain(),
        boltMode = protoDeviceConfig.boltMode.toDomain(),
        boardModel = protoDevice.model.toDomain(),
        supportedProfileCount = protoDevice.supportedProfileCount.toInt()
            .takeIf { it > 0 } ?: mappedProfiles.size,
    )
}

/**
 * The 0-based slot that `active_profile` names, or `null` when it is unset (0) or names no slot
 * below [profileCount]. On the wire `active_profile` is a 1-based index.
 */
internal fun DeviceConfig.activeProfileSlot(profileCount: Int): Int? =
    activeProfile.toInt().takeIf { it in 1..profileCount }?.let { it - 1 }

/**
 * The boot text as the domain holds it. The value may arrive NUL-padded from a fixed-width firmware
 * buffer, so it stops at the first NUL: padding must not render or falsely diverge from the clean
 * local string. An empty value falls back to the "SnapshotPB" default that the proto documents.
 */
internal fun DeviceConfig.bootTextToDomain(): String =
    bootText.substringBefore(Char.MIN_VALUE).takeIf { it.isNotEmpty() } ?: DEFAULT_BOOT_TEXT

/** The boot text the proto documents for an empty value. */
private const val DEFAULT_BOOT_TEXT = "SnapshotPB"

/**
 * Converts a domain Device to the writable protocol DeviceConfig message (active profile, boot
 * text, language, screen brightness, screen-off delay and display window). The read-only Device
 * message is never written by clients.
 *
 * `battery_type` is never set, so it goes out as `BATTERY_TYPE_UNSPECIFIED`. The app has no
 * battery setting, and the unset value leaves the board's own battery type alone. Do not send a
 * concrete default here: a write of `BATTERY_TYPE_9V` to a board with a 2S LiPo pack gives the
 * board the voltage thresholds of an alkaline cell, and it can discharge the pack too far.
 *
 * `display_window` is absent when the app holds no rectangle, which leaves the board's current
 * window alone. A rectangle the app does hold is clamped first: the board REJECTS the whole message
 * when the rectangle is out of bounds, so an illegal one would also drop the other settings here.
 */
fun toProtocolDeviceConfig(
    domainDevice: com.snapshotpb.configuration.model.Device
): DeviceConfig {
    // active_profile is a 1-based index (0 = unset); 0 when no profile is active or the
    // active id doesn't resolve to a known slot.
    val activeProfileIndex = domainDevice.activeProfileId?.let { activeId ->
        domainDevice.knownProfiles.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
    }
    val activeProfile = activeProfileIndex?.let { it + 1 } ?: 0

    // An empty boot text falls back to the "SnapshotPB" default rather than writing a blank string.
    val bootText = domainDevice.bootText.takeIf { it.isNotEmpty() } ?: DEFAULT_BOOT_TEXT

    return DeviceConfig(
        activeProfile = activeProfile.toUInt(),
        // Byte-aware cap so the wire value never overflows the firmware char[11] buffer (finding #8).
        bootText = bootText.takeUtf8Bytes(BOOT_TEXT_MAX_BYTES),
        // An unknown language (null) sends UNSPECIFIED, which leaves the board's language alone.
        language = domainDevice.language?.toProtocol() ?: Language.LANGUAGE_UNSPECIFIED,
        // The same rule for the screen settings: the unset value leaves the board's own alone.
        screenBrightness = domainDevice.screenBrightness?.toProtocol()
            ?: ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED,
        // A delay outside the firmware range sends 0, which leaves the board's own delay alone.
        screenOffDelay = (domainDevice.screenOffDelay.clampedScreenOffDelay() ?: 0).toUInt(),
        // An absent rectangle leaves the board's own window alone. A message field has explicit
        // presence, so a client that writes only boot text cannot move the window by omission.
        // toProtocol clamps, so an out-of-bounds value cannot make the board drop this message.
        displayWindow = domainDevice.displayWindow?.toProtocol(),
        // An unknown bolt mode (null) sends UNSPECIFIED, which leaves the board's bolt mode alone.
        boltMode = domainDevice.boltMode?.toProtocol() ?: BoltMode.BOLT_MODE_UNSPECIFIED,
    )
}

/**
 * Converts a domain Device's profiles to the protocol Profiles message.
 *
 * The proto `Profile` carries a board-owned per-profile field the domain model does not model
 * (`board_auto_off`). Because a save rewrites the whole `Profiles` object, building each profile
 * fresh would reset that field to its proto default, silently wiping it across all profiles
 * (finding #7). [lastReadProfiles] is the `Profiles` proto last read from this board; each freshly
 * built profile inherits the value of the board slot it came from (see [boardSlotSources]). The
 * default is used when no last-read value is available.
 *
 * `screen_brightness` was such a field on `Profile` tag 5. It now lives on `DeviceConfig`, the tag
 * is reserved, and a board ignores a client that still writes it.
 */
fun toProtocolProfiles(
    domainDevice: com.snapshotpb.configuration.model.Device,
    lastReadProfiles: Profiles? = null,
): Profiles = toProtocolProfilesWire(domainDevice, lastReadProfiles?.toWire()).toGenerated()

/**
 * The wire form of [toProtocolProfiles], which the engine writes. It keeps the raw enum numbers of
 * [lastReadProfiles], so a fire mode or an eye mode that this app does not know goes back to the
 * board unchanged unless the user selected a different mode (see [withBoardOwnedFieldsFrom]).
 */
internal fun toProtocolProfilesWire(
    domainDevice: com.snapshotpb.configuration.model.Device,
    lastReadProfiles: ProfilesWire?,
): ProfilesWire {
    val boardProfiles = lastReadProfiles?.profiles.orEmpty()
    val sources = boardSlotSources(domainDevice.id, domainDevice.knownProfiles, boardProfiles)
    return ProfilesWire(
        profiles = domainDevice.knownProfiles.mapIndexed { index, profile ->
            val board = sources[index]?.let { slot -> boardProfiles[slot] }
            profile.toProtocol().toWire().withBoardOwnedFieldsFrom(profile, board)
        }
    )
}

/**
 * Copies the board-owned fields of [board] (the board slot that [local] came from) onto this freshly
 * built profile.
 *
 * - `board_auto_off` always comes from the board (finding #7).
 * - A fire mode or an eye mode that the board reports as `UNSPECIFIED`, or as a number that this
 *   app does not know, reads as [MarkerFireMode.UNKNOWN] or [MarkerEyeMode.UNKNOWN]. While [local]
 *   still holds `UNKNOWN`, the board's own number goes back, so a save never changes the mode of
 *   the board by itself. A mode that the user selected is written as selected. Without a board
 *   slot, `UNKNOWN` goes out as `UNSPECIFIED`.
 */
internal fun ProfileWire.withBoardOwnedFieldsFrom(local: MarkerProfile, board: ProfileWire?): ProfileWire {
    if (board == null) return this
    val boardArm = board.autococker
    val arm = autococker?.let { mine ->
        if (boardArm == null) {
            mine
        } else {
            mine.copy(
                fireMode = if (local.fireMode == MarkerFireMode.UNKNOWN) boardArm.fireMode else mine.fireMode,
                eyeMode = if (local.eyeMode == MarkerEyeMode.UNKNOWN) boardArm.eyeMode else mine.eyeMode,
            )
        }
    }
    return copy(boardAutoOff = board.boardAutoOff, autococker = arm)
}

/**
 * These profiles with each `UNKNOWN` mode replaced by the mode that [written] sent for the same
 * slot. [written] is the result of [toProtocolProfilesWire] for these profiles, so the slots pair
 * by index.
 *
 * A write sends the board's own number for an `UNKNOWN` mode. When the board now holds a known
 * mode (for example after "keep my values" on a conflict), the stored copy must record that mode.
 * If it keeps `UNKNOWN`, the next read shows the same conflict again. A number that this app does
 * not know reads as `UNKNOWN` again, so it stays `UNKNOWN`.
 */
internal fun List<MarkerProfile>.withModesAsWritten(written: ProfilesWire): List<MarkerProfile> =
    mapIndexed { index, profile ->
        val arm = written.profiles.getOrNull(index)?.autococker?.toGenerated() ?: return@mapIndexed profile
        profile.copy(
            fireMode = if (profile.fireMode == MarkerFireMode.UNKNOWN) arm.fireMode.toDomain() else profile.fireMode,
            eyeMode = if (profile.eyeMode == MarkerEyeMode.UNKNOWN) arm.eyeMode.toDomain() else profile.eyeMode,
        )
    }

/** Above this many board slots the alignment search stops, and the slots pair by index. */
private const val MAX_ALIGNED_SLOTS = 8

/** Weight of an equal profile name in [profileSimilarity]; a name identifies a profile best. */
private const val NAME_MATCH_WEIGHT = 4

/**
 * For each profile in [local], the index of the board slot in [board] that it came from, or `null`
 * for a profile that has no board slot (a profile added after the last read).
 *
 * Each profile records its source slot ([MarkerProfile.sourceSlot]) at the board read and at each
 * write, and keeps it through a delete and an add. When at least one profile in [local] has a
 * source slot, that record decides: a profile pairs with its own source slot, and a profile with
 * none pairs with no slot. A slot out of the range of [board], or a slot that an earlier profile
 * already took, pairs with no slot, so no slot goes to two profiles.
 *
 * A list with no source slot at all comes from a copy stored before the app kept source slots, or
 * from a peer on an older build. Only then does the pairing fall back to position and content
 * ([alignedBoardSlots]).
 */
internal fun boardSlotSources(
    deviceUuid: Uuid,
    local: List<MarkerProfile>,
    board: List<ProfileWire>,
): List<Int?> {
    if (local.any { it.sourceSlot != null }) {
        val taken = mutableSetOf<Int>()
        return local.map { profile ->
            profile.sourceSlot?.takeIf { slot -> slot in board.indices && taken.add(slot) }
        }
    }
    return alignedBoardSlots(deviceUuid, local, board)
}

/**
 * The fallback of [boardSlotSources] for profiles that have no source slot.
 *
 * The domain list index is the board slot. But a delete moves every later profile down one slot
 * and gives it the id of its new slot, so after a delete neither the index nor the id identifies
 * the source slot. A pairing by index then gives each moved profile the board-owned fields of the
 * profile that was in its new slot (L17).
 *
 * - When [local] has at least as many profiles as [board], the slots pair by index. An add goes to
 *   the end of the list, so a list that did not get shorter kept its slots. A delete and then an
 *   add also give a list of the same length, and this pairing is then wrong. Only the source
 *   slots prevent that.
 * - When [local] has fewer profiles, some were deleted. The profiles keep their order, so the result
 *   is the increasing pairing that makes the paired profiles most alike ([profileSimilarity]). On a
 *   tie the earlier pairing wins, so identical profiles pair by index.
 */
private fun alignedBoardSlots(
    deviceUuid: Uuid,
    local: List<MarkerProfile>,
    board: List<ProfileWire>,
): List<Int?> {
    if (local.size >= board.size || board.size > MAX_ALIGNED_SLOTS) {
        return local.indices.map { index -> index.takeIf { it < board.size } }
    }
    val boardDomain = board.mapIndexed { slot, wire -> wire.toGenerated().toDomain(deviceUuid, slot) }
    val scores = local.map { mine -> boardDomain.map { theirs -> profileSimilarity(mine, theirs) } }

    var best: List<Int> = local.indices.toList()
    var bestScore = -1
    val chosen = ArrayList<Int>(local.size)

    // Visits every increasing pairing in lexicographic order and keeps the first best one.
    fun search(index: Int, firstSlot: Int, score: Int) {
        if (index == local.size) {
            if (score > bestScore) {
                bestScore = score
                best = chosen.toList()
            }
            return
        }
        val lastSlot = board.size - (local.size - index)
        for (slot in firstSlot..lastSlot) {
            chosen += slot
            search(index + 1, slot + 1, score + scores[index][slot])
            chosen.removeAt(chosen.lastIndex)
        }
    }
    search(index = 0, firstSlot = 0, score = 0)
    return best
}

/** How alike two profiles are: one point for each equal setting, [NAME_MATCH_WEIGHT] for the name. */
private fun profileSimilarity(a: MarkerProfile, b: MarkerProfile): Int {
    val settings = listOf(
        a.fireMode == b.fireMode,
        a.fireRateCap == b.fireRateCap,
        a.eyeMode == b.eyeMode,
        a.eyeThreshold == b.eyeThreshold,
        a.eyeDebounce == b.eyeDebounce,
        a.searSolenoidDwell == b.searSolenoidDwell,
        a.searSolenoidDelay == b.searSolenoidDelay,
        a.cockingSolenoidDelay == b.cockingSolenoidDelay,
        a.cockingSolenoidDwellOpen == b.cockingSolenoidDwellOpen,
        a.cockingSolenoidDwellClosed == b.cockingSolenoidDwellClosed,
        a.rampingCustomMultiplier == b.rampingCustomMultiplier,
        a.rampingCustomTriggerPulls == b.rampingCustomTriggerPulls,
        a.rampingCustomROFMinimum == b.rampingCustomROFMinimum,
        a.triggerDebounceOn == b.triggerDebounceOn,
        a.triggerDebounceOff == b.triggerDebounceOff,
        a.cockingSolenoidTimeout == b.cockingSolenoidTimeout,
    ).count { it }
    val name = if (a.name == b.name) NAME_MATCH_WEIGHT else 0
    return settings + name
}
