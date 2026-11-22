package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unit tests for [EyeReading] and edge cases of [eyeThresholdRaw] and [eyeSeesBall]. */
class EyeReadingTest {

    @Test
    fun eyeSeesBall_unknownEyeModeGivesNull() {
        assertNull(eyeSeesBall(MarkerEyeMode.UNKNOWN, levelRaw = 0, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, thresholdPercent = 50))
        assertNull(eyeSeesBall(MarkerEyeMode.UNKNOWN, levelRaw = EYE_ADC_FULL_SCALE_RAW, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, thresholdPercent = 50))
    }

    @Test
    fun levelPercent_computesPercentInActiveState() {
        val zero = EyeReading(levelRaw = 0, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE)
        val full = EyeReading(levelRaw = EYE_ADC_FULL_SCALE_RAW, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE)

        assertEquals(0.0, zero.levelPercent)
        assertEquals(100.0, full.levelPercent)
    }

    @Test
    fun levelPercent_givesNullWhenLevelIsNull() {
        val reading = EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE)

        assertNull(reading.levelPercent)
    }

    @Test
    fun levelPercent_givesNullWhenStateIsNotActive() {
        val nonActiveStates = listOf(
            EyeSensorState.EYE_OFF,
            EyeSensorState.OVERRIDE_OFF,
            EyeSensorState.FAULT,
            EyeSensorState.UNKNOWN,
        )
        nonActiveStates.forEach { state ->
            val reading = EyeReading(levelRaw = 2048, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = state)
            assertNull(reading.levelPercent, "State $state must give a null levelPercent")
        }
    }

    @Test
    fun levelPercent_givesNullWhenFullScaleIsZeroOrNegative() {
        val zeroScale = EyeReading(levelRaw = 100, fullScaleRaw = 0, state = EyeSensorState.ACTIVE)
        val negativeScale = EyeReading(levelRaw = 100, fullScaleRaw = -1, state = EyeSensorState.ACTIVE)

        assertNull(zeroScale.levelPercent)
        assertNull(negativeScale.levelPercent)
    }

    @Test
    fun eyeThresholdRaw_givesZeroWhenFullScaleIsZeroOrNegative() {
        assertEquals(0, eyeThresholdRaw(fullScaleRaw = 0, thresholdPercent = 50))
        assertEquals(0, eyeThresholdRaw(fullScaleRaw = -10, thresholdPercent = 50))
    }

    @Test
    fun eyeThresholdRaw_negativePercentUsesDefault() {
        assertEquals(2047, eyeThresholdRaw(fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, thresholdPercent = -5))
    }

    @Test
    fun eyeThresholdRaw_largeFullScaleDoesNotOverflow() {
        assertEquals(Int.MAX_VALUE, eyeThresholdRaw(fullScaleRaw = Int.MAX_VALUE, thresholdPercent = 100))
    }
}
