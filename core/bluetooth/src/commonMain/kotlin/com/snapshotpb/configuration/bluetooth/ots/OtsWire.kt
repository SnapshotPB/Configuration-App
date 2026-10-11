package com.snapshotpb.configuration.bluetooth.ots

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import snapshotpb.autococker.v1.AutocockerConfig
import snapshotpb.autococker.v1.AutocockerFireMode
import snapshotpb.common.v1.EyeMode
import snapshotpb.v1.BoardModel
import snapshotpb.v1.Device
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles

/*
 * Wire-compatible copies of the generated `Device` and `Profiles` messages.
 *
 * A generated enum throws on a number that it does not list, so newer firmware with a new fire mode,
 * eye mode or board model would stop the whole connection sync. These copies read every enum field
 * as a plain number instead. The field numbers and the wire types are the same as in
 * `device.proto`, `profile.proto` and `autococker.proto`, so the bytes are the same in both
 * directions. Keep the fields in step with those files.
 *
 * The write path also encodes `Profiles` through these copies, so a fire mode or an eye mode that
 * this app does not know can go back to the board unchanged (see [withBoardOwnedFieldsFrom]).
 */

/** Wire copy of [Device]; [model] is the raw `BoardModel` number. */
@Serializable
internal class DeviceWire(
    @ProtoNumber(1) val deviceId: ByteArray = byteArrayOf(),
    @ProtoNumber(2) val lockoutEnabled: Boolean = false,
    @ProtoNumber(3) val model: Int = 0,
    @ProtoNumber(4) val supportedProfileCount: UInt = 0U,
)

/** Wire copy of [AutocockerConfig]; [fireMode] and [eyeMode] are the raw enum numbers. */
@Serializable
internal data class AutocockerConfigWire(
    @ProtoNumber(1) val fireMode: Int = 0,
    @ProtoNumber(2) val eyeMode: Int = 0,
    @ProtoNumber(3) val eyeThreshold: UInt = 0U,
    @ProtoNumber(4) val eyeDebounce: UInt = 0U,
    @ProtoNumber(5) val searSolenoidDwell: UInt = 0U,
    @ProtoNumber(6) val cockingSolenoidDelay: UInt = 0U,
    @ProtoNumber(7) val cockingSolenoidDwellOpen: UInt = 0U,
    @ProtoNumber(8) val cockingSolenoidDwellClosed: UInt = 0U,
    @ProtoNumber(9) val rampingCustomMultiplier: UInt = 0U,
    @ProtoNumber(10) val rampingCustomTriggerPulls: UInt = 0U,
    @ProtoNumber(11) val rampingCustomRofMinimum: UInt = 0U,
    @ProtoNumber(12) val triggerDebounceOn: UInt = 0U,
    @ProtoNumber(13) val triggerDebounceOff: UInt = 0U,
    @ProtoNumber(14) val cockingSolenoidTimeout: UInt = 0U,
)

/** Wire copy of [Profile]. Tag 2 and tag 5 are reserved in `profile.proto`. */
@Serializable
internal data class ProfileWire(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(3) val fireRateCap: UInt = 0U,
    @ProtoNumber(4) val boardAutoOff: UInt = 0U,
    @ProtoNumber(6) val autococker: AutocockerConfigWire? = null,
)

/** Wire copy of [Profiles]. */
@Serializable
internal data class ProfilesWire(
    @ProtoNumber(1) val profiles: List<ProfileWire> = emptyList(),
)

private val fireModesByProtoNumber: Map<Int, AutocockerFireMode> =
    protoNumbersOf(AutocockerFireMode.serializer(), AutocockerFireMode.entries)

private val fireModeNumbers: Map<AutocockerFireMode, Int> =
    fireModesByProtoNumber.entries.associate { (number, mode) -> mode to number }

private val eyeModesByProtoNumber: Map<Int, EyeMode> =
    protoNumbersOf(EyeMode.serializer(), EyeMode.entries)

private val eyeModeNumbers: Map<EyeMode, Int> =
    eyeModesByProtoNumber.entries.associate { (number, mode) -> mode to number }

private val boardModelsByProtoNumber: Map<Int, BoardModel> =
    protoNumbersOf(BoardModel.serializer(), BoardModel.entries)

/** A board model number that this app does not know maps to `BOARD_MODEL_UNSPECIFIED`. */
internal fun DeviceWire.toGenerated(): Device = Device(
    deviceId = deviceId,
    lockoutEnabled = lockoutEnabled,
    model = boardModelsByProtoNumber[model] ?: BoardModel.BOARD_MODEL_UNSPECIFIED,
    supportedProfileCount = supportedProfileCount,
)

/**
 * A fire mode or an eye mode number that this app does not know maps to its `UNSPECIFIED` value,
 * which the domain mapper reads as `UNKNOWN`. The raw number stays on this wire copy.
 */
internal fun AutocockerConfigWire.toGenerated(): AutocockerConfig = AutocockerConfig(
    fireMode = fireModesByProtoNumber[fireMode] ?: AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED,
    eyeMode = eyeModesByProtoNumber[eyeMode] ?: EyeMode.EYE_MODE_UNSPECIFIED,
    eyeThreshold = eyeThreshold,
    eyeDebounce = eyeDebounce,
    searSolenoidDwell = searSolenoidDwell,
    cockingSolenoidDelay = cockingSolenoidDelay,
    cockingSolenoidDwellOpen = cockingSolenoidDwellOpen,
    cockingSolenoidDwellClosed = cockingSolenoidDwellClosed,
    rampingCustomMultiplier = rampingCustomMultiplier,
    rampingCustomTriggerPulls = rampingCustomTriggerPulls,
    rampingCustomRofMinimum = rampingCustomRofMinimum,
    triggerDebounceOn = triggerDebounceOn,
    triggerDebounceOff = triggerDebounceOff,
    cockingSolenoidTimeout = cockingSolenoidTimeout,
)

internal fun AutocockerConfig.toWire(): AutocockerConfigWire = AutocockerConfigWire(
    fireMode = fireModeNumbers.getValue(fireMode),
    eyeMode = eyeModeNumbers.getValue(eyeMode),
    eyeThreshold = eyeThreshold,
    eyeDebounce = eyeDebounce,
    searSolenoidDwell = searSolenoidDwell,
    cockingSolenoidDelay = cockingSolenoidDelay,
    cockingSolenoidDwellOpen = cockingSolenoidDwellOpen,
    cockingSolenoidDwellClosed = cockingSolenoidDwellClosed,
    rampingCustomMultiplier = rampingCustomMultiplier,
    rampingCustomTriggerPulls = rampingCustomTriggerPulls,
    rampingCustomRofMinimum = rampingCustomRofMinimum,
    triggerDebounceOn = triggerDebounceOn,
    triggerDebounceOff = triggerDebounceOff,
    cockingSolenoidTimeout = cockingSolenoidTimeout,
)

internal fun ProfileWire.toGenerated(): Profile = Profile(
    name = name,
    fireRateCap = fireRateCap,
    boardAutoOff = boardAutoOff,
    autococker = autococker?.toGenerated(),
)

internal fun Profile.toWire(): ProfileWire = ProfileWire(
    name = name,
    fireRateCap = fireRateCap,
    boardAutoOff = boardAutoOff,
    autococker = autococker?.toWire(),
)

internal fun ProfilesWire.toGenerated(): Profiles = Profiles(profiles = profiles.map { it.toGenerated() })

/**
 * A `null` slot in a generated list becomes an empty profile, so the slot positions stay the same.
 * Only a hand-built message can hold a `null` slot: the wire has no way to express one.
 */
internal fun Profiles.toWire(): ProfilesWire =
    ProfilesWire(profiles = profiles.map { it?.toWire() ?: ProfileWire() })
