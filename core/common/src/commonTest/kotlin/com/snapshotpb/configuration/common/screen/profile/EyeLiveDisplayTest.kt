package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure selection functions of [EyeLiveDisplay]. */
class EyeLiveDisplayTest {

    @Test
    fun eyeLiveDisplay_nullWaitingAndFailedGiveNone() {
        assertEquals(EyeLiveDisplay.None, eyeLiveDisplay(null, MarkerEyeMode.BREAK_BEAM, 50))
        assertEquals(
            EyeLiveDisplay.None,
            eyeLiveDisplay(EyeReadingState.Waiting, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            EyeLiveDisplay.None,
            eyeLiveDisplay(EyeReadingState.Failed, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun eyeLiveDisplay_unsupportedAndNotPermittedGiveHints() {
        assertEquals(
            EyeLiveDisplay.Hint(EyeLiveHint.UNSUPPORTED),
            eyeLiveDisplay(EyeReadingState.Unsupported, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            EyeLiveDisplay.Hint(EyeLiveHint.NOT_PERMITTED),
            eyeLiveDisplay(EyeReadingState.NotPermitted, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun eyeLiveDisplay_inactiveSensorStatesGiveHintsEvenWithALevel() {
        assertEquals(
            EyeLiveDisplay.Hint(EyeLiveHint.EYE_OFF),
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.EYE_OFF),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
        assertEquals(
            EyeLiveDisplay.Hint(EyeLiveHint.OVERRIDE_OFF),
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(
                        levelRaw = 1000,
                        fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
                        state = EyeSensorState.OVERRIDE_OFF,
                    ),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
        assertEquals(
            EyeLiveDisplay.Hint(EyeLiveHint.FAULT),
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.FAULT),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
    }

    @Test
    fun eyeLiveDisplay_unknownStateMissingLevelOrZeroFullScaleGiveNone() {
        assertEquals(
            EyeLiveDisplay.None,
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.UNKNOWN),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
        assertEquals(
            EyeLiveDisplay.None,
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
        assertEquals(
            EyeLiveDisplay.None,
            eyeLiveDisplay(
                EyeReadingState.Reading(
                    EyeReading(levelRaw = 1000, fullScaleRaw = 0, state = EyeSensorState.ACTIVE),
                ),
                MarkerEyeMode.BREAK_BEAM,
                50,
            ),
        )
    }

    @Test
    fun eyeLiveDisplay_breakBeamAndReflectiveFollowTheRawThresholdBoundary() {
        val atThreshold = EyeReadingState.Reading(
            EyeReading(levelRaw = 2047, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )
        val aboveThreshold = EyeReadingState.Reading(
            EyeReading(levelRaw = 2048, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )

        assertEquals(
            true,
            assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(atThreshold, MarkerEyeMode.BREAK_BEAM, 50)).seesBall,
        )
        assertEquals(
            false,
            assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(aboveThreshold, MarkerEyeMode.BREAK_BEAM, 50)).seesBall,
        )
        assertEquals(
            false,
            assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(atThreshold, MarkerEyeMode.REFLECTIVE, 50)).seesBall,
        )
        assertEquals(
            true,
            assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(aboveThreshold, MarkerEyeMode.REFLECTIVE, 50)).seesBall,
        )
    }

    @Test
    fun eyeLiveDisplay_editedThresholdChangesTheResultAtOnce() {
        val reading = EyeReadingState.Reading(
            EyeReading(levelRaw = 1500, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )

        val atDefault = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.BREAK_BEAM, 50))
        assertEquals(true, atDefault.seesBall)

        val atLowerThreshold = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.BREAK_BEAM, 30))
        assertEquals(false, atLowerThreshold.seesBall)
    }

    @Test
    fun eyeLiveDisplay_thresholdZeroUsesDefaultAndAboveMaxClampsToMax() {
        val reading = EyeReadingState.Reading(
            EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )

        val zeroThreshold = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.BREAK_BEAM, 0))
        assertEquals(50, zeroThreshold.thresholdPercent)
        assertEquals(2047f / 4095f, zeroThreshold.thresholdFraction)

        val highThreshold = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.BREAK_BEAM, 150))
        assertEquals(100, highThreshold.thresholdPercent)
        assertEquals(1f, highThreshold.thresholdFraction)
    }

    @Test
    fun eyeLiveDisplay_computesFractionsAndKeepsOutOfRangeLevelInZeroToOne() {
        val normalReading = EyeReadingState.Reading(
            EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )
        val normal = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(normalReading, MarkerEyeMode.BREAK_BEAM, 50))
        assertEquals(1000 * 100.0 / 4095, normal.levelPercent)
        assertEquals(1000f / 4095f, normal.levelFraction)
        assertEquals(2047f / 4095f, normal.thresholdFraction)

        val overFullScale = EyeReadingState.Reading(
            EyeReading(levelRaw = 5000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )
        val clampedHigh = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(overFullScale, MarkerEyeMode.BREAK_BEAM, 100))
        assertEquals(1f, clampedHigh.levelFraction)
        assertEquals(5000 * 100.0 / 4095, clampedHigh.levelPercent)
        assertEquals(false, clampedHigh.seesBall)

        val negativeLevel = EyeReadingState.Reading(
            EyeReading(levelRaw = -10, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )
        val clampedLow = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(negativeLevel, MarkerEyeMode.BREAK_BEAM, 50))
        assertEquals(0f, clampedLow.levelFraction)
        assertEquals(-10 * 100.0 / 4095, clampedLow.levelPercent)
        assertEquals(true, clampedLow.seesBall)
    }

    @Test
    fun eyeLiveDisplay_unknownAndOffEyeModesGiveLevelWithNullSeesBall() {
        val reading = EyeReadingState.Reading(
            EyeReading(levelRaw = 1000, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )

        val unknownMode = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.UNKNOWN, 50))
        assertNull(unknownMode.seesBall)

        val offMode = assertIs<EyeLiveDisplay.Level>(eyeLiveDisplay(reading, MarkerEyeMode.OFF, 50))
        assertNull(offMode.seesBall)
    }

    @Test
    fun formatEyeLevelPercent_formatsWithOneDecimalPlace() {
        assertEquals("0.0", formatEyeLevelPercent(0.0))
        assertEquals("24.4", formatEyeLevelPercent(1000 * 100.0 / 4095))
        assertEquals("100.0", formatEyeLevelPercent(100.0))
        assertEquals("24.5", formatEyeLevelPercent(24.45))
    }

    @Test
    fun profileWatchesEye_followsEyeThresholdVisibility() {
        assertFalse(profileWatchesEye(null, MarkerBoltMode.CLOSED))
        assertFalse(profileWatchesEye(testProfile().copy(eyeMode = MarkerEyeMode.OFF), MarkerBoltMode.CLOSED))
        assertTrue(profileWatchesEye(testProfile().copy(eyeMode = MarkerEyeMode.BREAK_BEAM), MarkerBoltMode.CLOSED))
        assertTrue(profileWatchesEye(testProfile().copy(eyeMode = MarkerEyeMode.REFLECTIVE), MarkerBoltMode.OPEN))
        assertTrue(profileWatchesEye(testProfile().copy(eyeMode = MarkerEyeMode.UNKNOWN), MarkerBoltMode.CLOSED))
    }
}
