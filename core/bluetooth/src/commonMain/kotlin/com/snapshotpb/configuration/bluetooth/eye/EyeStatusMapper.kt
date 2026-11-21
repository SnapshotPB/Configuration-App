package com.snapshotpb.configuration.bluetooth.eye

import com.snapshotpb.configuration.bluetooth.ots.protoNumbersOf
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import snapshotpb.v1.EyeStatus
import snapshotpb.v1.EyeSensorState as ProtoEyeSensorState

/** The wire copy of [EyeStatus] that keeps the state as a number. */
@Serializable
internal data class EyeStatusWire(
    @ProtoNumber(1) val levelRaw: UInt? = null,
    @ProtoNumber(2) val fullScaleRaw: UInt = 0U,
    @ProtoNumber(3) val state: Int = 0,
)

private val eyeSensorStatesByProtoNumber: Map<Int, ProtoEyeSensorState> =
    protoNumbersOf(ProtoEyeSensorState.serializer(), ProtoEyeSensorState.entries)

/** A state number that the generated enum does not know maps to `EYE_SENSOR_STATE_UNSPECIFIED`. */
internal fun EyeStatusWire.toGenerated(): EyeStatus = EyeStatus(
    levelRaw = levelRaw,
    fullScaleRaw = fullScaleRaw,
    state = eyeSensorStatesByProtoNumber[state] ?: ProtoEyeSensorState.EYE_SENSOR_STATE_UNSPECIFIED,
)

/**
 * Maps a protocol [EyeStatus] to a domain [EyeReading].
 *
 * `UNSPECIFIED` maps to [EyeSensorState.UNKNOWN]. Each other state maps to the same name. A raw
 * value above [Int.MAX_VALUE] becomes [Int.MAX_VALUE].
 */
internal fun EyeStatus.toEyeReading(): EyeReading = EyeReading(
    levelRaw = levelRaw?.toClampedInt(),
    fullScaleRaw = fullScaleRaw.toClampedInt(),
    state = state.toDomain(),
)

/**
 * Decodes protobuf [bytes] to an [EyeReading].
 *
 * This function returns `null` when [bytes] is not a valid message. It never throws for bad bytes
 * and writes no log entries.
 */
internal fun decodeEyeStatus(bytes: ByteArray): EyeReading? = try {
    ProtoBuf.decodeFromByteArray(EyeStatusWire.serializer(), bytes).toGenerated().toEyeReading()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun ProtoEyeSensorState.toDomain(): EyeSensorState = when (this) {
    ProtoEyeSensorState.EYE_SENSOR_STATE_ACTIVE -> EyeSensorState.ACTIVE
    ProtoEyeSensorState.EYE_SENSOR_STATE_EYE_OFF -> EyeSensorState.EYE_OFF
    ProtoEyeSensorState.EYE_SENSOR_STATE_OVERRIDE_OFF -> EyeSensorState.OVERRIDE_OFF
    ProtoEyeSensorState.EYE_SENSOR_STATE_FAULT -> EyeSensorState.FAULT
    ProtoEyeSensorState.EYE_SENSOR_STATE_UNSPECIFIED -> EyeSensorState.UNKNOWN
}

private fun UInt.toClampedInt(): Int = coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()
