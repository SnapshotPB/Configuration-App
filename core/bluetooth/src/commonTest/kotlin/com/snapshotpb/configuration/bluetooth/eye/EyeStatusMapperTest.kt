package com.snapshotpb.configuration.bluetooth.eye

import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.EyeStatus
import snapshotpb.v1.EyeSensorState as ProtoEyeSensorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EyeStatusMapperTest {

    @Test
    fun eachGeneratedStateMapsToTheExpectedDomainState() {
        assertEquals(
            EyeSensorState.ACTIVE,
            EyeStatus(state = ProtoEyeSensorState.EYE_SENSOR_STATE_ACTIVE).toEyeReading().state,
        )
        assertEquals(
            EyeSensorState.EYE_OFF,
            EyeStatus(state = ProtoEyeSensorState.EYE_SENSOR_STATE_EYE_OFF).toEyeReading().state,
        )
        assertEquals(
            EyeSensorState.OVERRIDE_OFF,
            EyeStatus(state = ProtoEyeSensorState.EYE_SENSOR_STATE_OVERRIDE_OFF).toEyeReading().state,
        )
        assertEquals(
            EyeSensorState.FAULT,
            EyeStatus(state = ProtoEyeSensorState.EYE_SENSOR_STATE_FAULT).toEyeReading().state,
        )
        assertEquals(
            EyeSensorState.UNKNOWN,
            EyeStatus(state = ProtoEyeSensorState.EYE_SENSOR_STATE_UNSPECIFIED).toEyeReading().state,
        )

        val knownProtoStates = ProtoEyeSensorState.entries - ProtoEyeSensorState.EYE_SENSOR_STATE_UNSPECIFIED
        val mappedDomainStates = knownProtoStates.map { EyeStatus(state = it).toEyeReading().state }
        assertEquals(
            (EyeSensorState.entries - EyeSensorState.UNKNOWN).toSet(),
            mappedDomainStates.toSet(),
        )
    }

    @Test
    fun unknownStateNumberDecodesToUnknown() {
        val bytes = ProtoBuf.encodeToByteArray(
            EyeStatusWire.serializer(),
            EyeStatusWire(fullScaleRaw = 4095U, state = 9),
        )

        val reading = decodeEyeStatus(bytes)

        assertEquals(
            EyeReading(levelRaw = null, fullScaleRaw = 4095, state = EyeSensorState.UNKNOWN),
            reading,
        )
    }

    @Test
    fun levelZeroIsPresentAndAbsentLevelIsNull() {
        val withZero = ProtoBuf.encodeToByteArray(
            EyeStatusWire.serializer(),
            EyeStatusWire(levelRaw = 0U, fullScaleRaw = 4095U, state = 1),
        )
        val decodedZero = assertNotNull(decodeEyeStatus(withZero))
        assertEquals(0, decodedZero.levelRaw)
        assertEquals(4095, decodedZero.fullScaleRaw)
        assertEquals(EyeSensorState.ACTIVE, decodedZero.state)

        val withoutLevel = ProtoBuf.encodeToByteArray(
            EyeStatusWire.serializer(),
            EyeStatusWire(levelRaw = null, fullScaleRaw = 4095U, state = 1),
        )
        val decodedAbsent = assertNotNull(decodeEyeStatus(withoutLevel))
        assertNull(decodedAbsent.levelRaw)
        assertEquals(4095, decodedAbsent.fullScaleRaw)
        assertEquals(EyeSensorState.ACTIVE, decodedAbsent.state)
    }

    @Test
    fun levelRawStaysAsSentInEveryState() {
        val bytes = ProtoBuf.encodeToByteArray(
            EyeStatusWire.serializer(),
            EyeStatusWire(levelRaw = 123U, fullScaleRaw = 4095U, state = 2),
        )

        assertEquals(
            EyeReading(levelRaw = 123, fullScaleRaw = 4095, state = EyeSensorState.EYE_OFF),
            decodeEyeStatus(bytes),
        )
    }

    @Test
    fun rawValuesAboveIntMaxValueBecomeIntMaxValue() {
        val overMax = Int.MAX_VALUE.toUInt() + 1U
        val direct = EyeStatus(
            levelRaw = overMax,
            fullScaleRaw = UInt.MAX_VALUE,
            state = ProtoEyeSensorState.EYE_SENSOR_STATE_ACTIVE,
        ).toEyeReading()
        assertEquals(Int.MAX_VALUE, direct.levelRaw)
        assertEquals(Int.MAX_VALUE, direct.fullScaleRaw)

        val bytes = ProtoBuf.encodeToByteArray(
            EyeStatusWire.serializer(),
            EyeStatusWire(levelRaw = UInt.MAX_VALUE, fullScaleRaw = overMax, state = 1),
        )
        val decoded = assertNotNull(decodeEyeStatus(bytes))
        assertEquals(Int.MAX_VALUE, decoded.levelRaw)
        assertEquals(Int.MAX_VALUE, decoded.fullScaleRaw)
    }

    @Test
    fun badBytesReturnNull() {
        assertNull(decodeEyeStatus(byteArrayOf(0x0A, 0x05, 0x01)))
        assertNull(decodeEyeStatus(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun emptyArrayDecodesToUnknownWithZeroFullScaleAndNullLevel() {
        assertEquals(
            EyeReading(levelRaw = null, fullScaleRaw = 0, state = EyeSensorState.UNKNOWN),
            decodeEyeStatus(byteArrayOf()),
        )
    }
}
