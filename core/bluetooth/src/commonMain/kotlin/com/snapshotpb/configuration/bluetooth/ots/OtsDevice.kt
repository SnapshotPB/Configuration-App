package com.snapshotpb.configuration.bluetooth.ots


import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import snapshotpb.v1.Device
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.DisplayWindow
import snapshotpb.v1.Language
import snapshotpb.v1.Profiles
import snapshotpb.v1.ScreenBrightness

/** Largest shift a 32-bit length varint can use before it would overflow [Int]. */
private const val MAX_VARINT_SHIFT = 28

fun trimProtobufPadding(bytes: ByteArray): ByteArray {
    var pos = 0
    while (pos < bytes.size) {
        if (bytes[pos] == 0.toByte()) {
            return bytes.copyOf(pos)
        }
        val tagPos = pos
        var tag = 0
        var shift = 0
        var ok = false
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt()
            tag = tag or ((b and 0x7f) shl shift)
            if ((b and 0x80) == 0) {
                ok = true
                break
            }
            shift += 7
        }
        if (!ok || tag == 0) {
            return bytes.copyOf(tagPos)
        }
        val wireType = tag and 0x07
        when (wireType) {
            0 -> {
                var varintOk = false
                while (pos < bytes.size) {
                    val b = bytes[pos++].toInt()
                    if ((b and 0x80) == 0) {
                        varintOk = true
                        break
                    }
                }
                if (!varintOk) return bytes.copyOf(tagPos)
            }
            1 -> {
                if (pos + 8 > bytes.size) return bytes.copyOf(tagPos)
                pos += 8
            }
            2 -> {
                // Bound the shift and reject a negative/overlong result. Without this a corrupt or
                // hostile length (five continuation bytes reaching bit 31) overflows Int to a
                // negative value, `pos + length > size` is false, and `pos += length` walks the
                // cursor backwards into a negative index on the next iteration.
                var length = 0
                var lengthShift = 0
                var lenOk = false
                while (pos < bytes.size && lengthShift <= MAX_VARINT_SHIFT) {
                    val b = bytes[pos++].toInt()
                    length = length or ((b and 0x7f) shl lengthShift)
                    if ((b and 0x80) == 0) {
                        lenOk = true
                        break
                    }
                    lengthShift += 7
                }
                if (!lenOk || length < 0 || pos + length > bytes.size) return bytes.copyOf(tagPos)
                pos += length
            }
            5 -> {
                if (pos + 4 > bytes.size) return bytes.copyOf(tagPos)
                pos += 4
            }
            else -> {
                return bytes.copyOf(tagPos)
            }
        }
    }
    return bytes
}

/**
 * Decodes the raw bytes of a "Device" OTS object into the typed [Device] protobuf message. It reads
 * through [DeviceWire], so a board model number that this app does not know decodes as
 * `BOARD_MODEL_UNSPECIFIED` and does not fail the connection sync.
 */
fun decodeDevice(bytes: ByteArray): Device {
    val trimmed = trimProtobufPadding(bytes)
    return ProtoBuf.decodeFromByteArray(DeviceWire.serializer(), trimmed).toGenerated()
}

/**
 * Wire-compatible copy of [DeviceConfig], used only to decode. It reads every enum field as a plain
 * number, because a generated enum throws on a number that it does not list. Newer firmware can
 * report a language or a brightness that this app does not know, and that must not fail the whole
 * connection sync. Keep the fields in step with `device_config.proto`.
 *
 * Tag 7 (`battery_type`) is not here on purpose. The app has no battery setting, and the decoder
 * ignores a tag that this class does not list. A write sends the unset value, which leaves the
 * board's own battery type alone (see [toProtocolDeviceConfig]).
 */
@Serializable
private class DeviceConfigWire(
    @ProtoNumber(1) val activeProfile: UInt = 0U,
    @ProtoNumber(2) val bootText: String = "",
    @ProtoNumber(3) val displayWindow: DisplayWindow? = null,
    @ProtoNumber(4) val language: Int = 0,
    @ProtoNumber(5) val screenBrightness: Int = 0,
    @ProtoNumber(6) val screenOffDelay: UInt = 0U,
)

/**
 * Each entry of a generated proto enum by its proto number, read from the serializer descriptor so
 * the table cannot drift from the schema.
 */
internal fun <T : Enum<T>> protoNumbersOf(
    serializer: KSerializer<T>,
    entries: List<T>,
): Map<Int, T> {
    val descriptor = serializer.descriptor
    return entries.associateBy { entry ->
        // These tables are top-level vals in this file, so a throw here would be an
        // ExceptionInInitializerError that takes decodeDevice and decodeProfiles down with it. The
        // generated code annotates every entry, and a proto3 enum numbers its entries in
        // declaration order, so the ordinal is the right fallback if one ever lacks the annotation.
        descriptor.getElementAnnotations(entry.ordinal)
            .filterIsInstance<ProtoNumber>()
            .singleOrNull()
            ?.number
            ?: entry.ordinal
    }
}

private val languagesByProtoNumber: Map<Int, Language> =
    protoNumbersOf(Language.serializer(), Language.entries)

private val brightnessesByProtoNumber: Map<Int, ScreenBrightness> =
    protoNumbersOf(ScreenBrightness.serializer(), ScreenBrightness.entries)

/**
 * Decodes the raw bytes of a "device_config" OTS object into the typed [DeviceConfig] message. A
 * language or brightness number that this app does not know decodes as its `UNSPECIFIED` value: the
 * board holds no value that the app can show.
 */
fun decodeDeviceConfig(bytes: ByteArray): DeviceConfig {
    val trimmed = trimProtobufPadding(bytes)
    val wire = ProtoBuf.decodeFromByteArray(DeviceConfigWire.serializer(), trimmed)
    return DeviceConfig(
        activeProfile = wire.activeProfile,
        bootText = wire.bootText,
        displayWindow = wire.displayWindow,
        language = languagesByProtoNumber[wire.language] ?: Language.LANGUAGE_UNSPECIFIED,
        screenBrightness = brightnessesByProtoNumber[wire.screenBrightness]
            ?: ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED,
        screenOffDelay = wire.screenOffDelay,
    )
}

/**
 * Decodes the raw bytes of a "Profiles" OTS object into its wire copy, which keeps each enum as a
 * plain number. A fire mode or an eye mode that this app does not know therefore does not fail the
 * connection sync, and the write path can send the number back unchanged.
 */
internal fun decodeProfilesWire(bytes: ByteArray): ProfilesWire {
    val trimmed = trimProtobufPadding(bytes)
    return ProtoBuf.decodeFromByteArray(ProfilesWire.serializer(), trimmed)
}

/**
 * Decodes the raw bytes of a "Profiles" OTS object into the typed [Profiles] protobuf message. An
 * enum number that this app does not know decodes as its `UNSPECIFIED` value.
 */
fun decodeProfiles(bytes: ByteArray): Profiles = decodeProfilesWire(bytes).toGenerated()

/**
 * Selects, reads, and decodes the read-only "device" object from the peripheral using OTS.
 */
suspend fun OtsClient.readDeviceObject(): Device = transaction {
    if (!selectObjectByNameForRead("device")) {
        throw OtsObjectNotFound("device")
    }
    decodeDevice(readSelectedObjectBytes())
}

/**
 * Selects, reads, and decodes the writable "device_config" object (active profile, boot text and
 * language) from the peripheral using OTS.
 */
suspend fun OtsClient.readDeviceConfigObject(): DeviceConfig = transaction {
    if (!selectObjectByNameForRead("device_config")) {
        throw OtsObjectNotFound("device_config")
    }
    decodeDeviceConfig(readSelectedObjectBytes())
}

/**
 * Reads the "device_config" object back just after [writeDeviceConfiguration]. The selection rides
 * out the firmware's commit window, as the write's own second selection does.
 */
suspend fun OtsClient.readDeviceConfigObjectAfterCommit(): DeviceConfig = transaction {
    if (!selectObjectByNameAfterCommit("device_config")) {
        throw OtsObjectNotFound("device_config")
    }
    decodeDeviceConfig(readSelectedObjectBytes())
}

/**
 * Selects, reads, and decodes the standard "profiles" object from the peripheral using OTS. The
 * result is the wire copy, so an enum number that this app does not know survives to the next write.
 */
internal suspend fun OtsClient.readDeviceProfiles(): ProfilesWire = transaction {
    if (!selectObjectByNameForRead("profiles")) {
        throw OtsObjectNotFound("profiles")
    }
    decodeProfilesWire(readSelectedObjectBytes())
}

/**
 * Encodes and writes the writable "device_config" and "profiles" objects back to the peripheral
 * using OTS. The read-only "device" object (id, model, lockout, slot count) is never written.
 *
 * Both objects go in one [OtsClient.transaction], so no other selection can come between them.
 */
internal suspend fun OtsClient.writeDeviceConfiguration(
    deviceConfig: DeviceConfig,
    profiles: ProfilesWire
) = transaction {
    // 1. Encode settings
    val deviceConfigBytes = ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), deviceConfig)
    val profilesBytes = ProtoBuf.encodeToByteArray(ProfilesWire.serializer(), profiles)

    // 2. Write DeviceConfig object
    if (!selectObjectByName("device_config")) {
        throw OtsObjectNotFound("device_config")
    }
    writeSelectedObjectBytes(deviceConfigBytes)

    // 3. Write Profiles object.
    // The firmware commits a just-written object asynchronously and stops
    // servicing OLCP until it finishes; a selection issued mid-commit is
    // dropped (not queued), so the board must be given time to settle and the
    // selection re-issued rather than failing the whole write on one timeout.
    if (!selectObjectByNameAfterCommit("profiles")) {
        throw OtsObjectNotFound("profiles")
    }
    writeSelectedObjectBytes(profilesBytes)
}

/** Settle delay before each post-write OTS object selection; covers the firmware's commit window. */
private const val SELECT_AFTER_COMMIT_SETTLE_MILLIS = 750L

/** Selection attempts after a write before giving up — see [selectObjectByNameAfterCommit]. */
private const val SELECT_AFTER_COMMIT_ATTEMPTS = 3

/**
 * Selects an OTS object by name immediately after a preceding object write.
 *
 * The vendor firmware applies a written object asynchronously and ignores OLCP
 * commands while it commits, dropping any selection issued during that window.
 * This settles before each attempt and re-issues the selection so a
 * transiently-busy board recovers instead of failing the write on one timeout.
 *
 * A healthy board recovers on the first attempt (~[SELECT_AFTER_COMMIT_SETTLE_MILLIS]); the
 * remaining attempts only burn time against a genuinely wedged board, where each failed
 * [selectObjectByName] also waits out one OLCP control-response timeout (a Go To that gets no
 * response keeps the object's ID and does not go on to the walk). Kept deliberately small
 * so a stuck write surfaces an error in seconds rather than tens of seconds.
 */
private suspend fun OtsClient.selectObjectByNameAfterCommit(
    name: String,
    attempts: Int = SELECT_AFTER_COMMIT_ATTEMPTS,
    settleMillis: Long = SELECT_AFTER_COMMIT_SETTLE_MILLIS,
): Boolean {
    repeat(attempts) {
        delay(settleMillis)
        if (selectObjectByName(name)) return true
    }
    return false
}

/** Selection attempts for a read before giving up — see [selectObjectByNameForRead]. */
private const val SELECT_FOR_READ_ATTEMPTS = 3

/** Brief settle between read-selection retries; just rides out a transient read glitch. */
private const val SELECT_FOR_READ_RETRY_MILLIS = 150L

/**
 * Selects an OTS object by name for a read, retrying on a transient miss.
 *
 * [selectObjectByName] uses one OLCP Go To when the object's ID is known, and otherwise walks the
 * object list via OLCP First/Next, reading each object's name over GATT. On iOS an individual
 * metadata read occasionally glitches so the walk misses the target and reports "not found", and
 * a control-point indication can be lost; re-issuing the whole selection recovers. This is the read-path analogue of
 * [selectObjectByNameAfterCommit] (which instead rides out the firmware's post-write commit window),
 * so the first attempt runs immediately and only retries pay the settle delay.
 */
private suspend fun OtsClient.selectObjectByNameForRead(
    name: String,
    attempts: Int = SELECT_FOR_READ_ATTEMPTS,
    retryMillis: Long = SELECT_FOR_READ_RETRY_MILLIS,
): Boolean {
    repeat(attempts) { attempt ->
        if (selectObjectByName(name)) return true
        if (attempt < attempts - 1) delay(retryMillis)
    }
    return false
}
