package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Cover for the stepper table the watch apps edit a profile with: the limits, the fire-rate-cap
 * jump, the visibility rules, and the agreement between the table and the firmware clamp.
 */
class MarkerProfileFieldTest {

    private val id = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun profile(
        fireMode: MarkerFireMode = MarkerFireMode.SEMI,
        eyeMode: MarkerEyeMode = MarkerEyeMode.REFLECTIVE,
    ) = MarkerProfile(id = id, name = "P", fireMode = fireMode, eyeMode = eyeMode)

    /** A profile that shows every field: an eye is on and the fire mode is the custom ramping mode. */
    private fun allShown() = profile(fireMode = MarkerFireMode.CUSTOM_RAMPING)

    private val rampingFields = listOf(
        MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER,
        MarkerProfileField.RAMPING_CUSTOM_TRIGGER_PULLS,
        MarkerProfileField.RAMPING_CUSTOM_ROF_MINIMUM,
    )

    // --- step limits ---

    @Test
    fun step_holdsTheValueInsideTheLimits() {
        MarkerProfileField.entries.filter { it != MarkerProfileField.FIRE_RATE_CAP }.forEach { field ->
            assertEquals(field.min, field.step(field.min, -1), "$field steps below min")
            assertEquals(field.max, field.step(field.max, +1), "$field steps above max")
            assertEquals(field.min + 1, field.step(field.min, +1), "$field fine step up")
            assertEquals(
                field.min + MarkerProfileField.COARSE_STEP_FACTOR,
                field.step(field.min, MarkerProfileField.COARSE_STEP_FACTOR),
                "$field coarse step up",
            )
        }
    }

    @Test
    fun step_theFieldsWithAZeroSentinelStopAtOne() {
        // The app never sends 0 for these: the firmware would replace it with its own default.
        listOf(
            MarkerProfileField.EYE_THRESHOLD,
            MarkerProfileField.COCKING_SOLENOID_TIMEOUT,
            MarkerProfileField.TRIGGER_DEBOUNCE_ON,
            MarkerProfileField.TRIGGER_DEBOUNCE_OFF,
        ).forEach { field ->
            assertEquals(1, field.min, "$field min")
            assertEquals(1, field.step(3, -MarkerProfileField.COARSE_STEP_FACTOR), "$field coarse step down")
        }
    }

    @Test
    fun coarseStepIsFiveFineSteps() {
        assertEquals(5, MarkerProfileField.COARSE_STEP_FACTOR)
    }

    // --- fire-rate cap: 0 is uncapped, the lowest cap is RATE_CAP_MIN ---

    @Test
    fun step_fireRateCap_jumpsBetweenUncappedAndTheLowestCap() {
        val cap = MarkerProfileField.FIRE_RATE_CAP
        assertEquals(0, cap.step(RATE_CAP_MIN, -1), "fine step down from the lowest cap")
        assertEquals(0, cap.step(RATE_CAP_MIN, -MarkerProfileField.COARSE_STEP_FACTOR), "coarse step down")
        assertEquals(RATE_CAP_MIN, cap.step(0, +1), "fine step up from uncapped")
        assertEquals(RATE_CAP_MIN, cap.step(0, +MarkerProfileField.COARSE_STEP_FACTOR), "coarse step up")
        assertEquals(0, cap.step(0, -1), "uncapped is the floor")
    }

    @Test
    fun step_fireRateCap_stopsAtTheLowestCapBeforeItJumps() {
        val cap = MarkerProfileField.FIRE_RATE_CAP
        // A coarse step from 12 would land in the gap; it stops at the lowest cap first.
        assertEquals(RATE_CAP_MIN, cap.step(12, -MarkerProfileField.COARSE_STEP_FACTOR))
        assertEquals(RATE_CAP_MAX, cap.step(RATE_CAP_MAX, +1))
        assertEquals(RATE_CAP_MAX, cap.step(RATE_CAP_MAX - 2, +MarkerProfileField.COARSE_STEP_FACTOR))
        assertEquals(106, cap.step(105, +1))
    }

    @Test
    fun step_fireRateCap_leavesAValueInTheGapInOneStep() {
        // The phone can type 1 to 9; a stepper moves off such a value at once.
        val cap = MarkerProfileField.FIRE_RATE_CAP
        assertEquals(0, cap.step(5, -1))
        assertEquals(RATE_CAP_MIN, cap.step(5, +1))
        assertEquals(5, cap.step(5, 0), "a zero delta is no step")
    }

    // --- get, set and the firmware clamp agree ---

    @Test
    fun everyField_roundTripsThroughGetAndSet() {
        val base = allShown()
        MarkerProfileField.entries.forEach { field ->
            val value = field.min + 1
            val updated = field.set(base, value)
            assertEquals(value, field.get(updated), "$field get after set")
            // A set changes only its own field.
            MarkerProfileField.entries.filter { it != field }.forEach { other ->
                assertEquals(other.get(base), other.get(updated), "$field set changed $other")
            }
        }
    }

    @Test
    fun everyField_limitsSurviveTheFirmwareClamp() {
        val base = allShown()
        MarkerProfileField.entries.forEach { field ->
            assertEquals(
                field.min,
                field.get(field.set(base, field.min).clampedToFirmwareRanges()),
                "$field min is not a value the firmware keeps",
            )
            assertEquals(
                field.max,
                field.get(field.set(base, field.max).clampedToFirmwareRanges()),
                "$field max is not a value the firmware keeps",
            )
        }
    }

    @Test
    fun everyField_stepStaysInsideTheFirmwareClamp() {
        val base = allShown()
        MarkerProfileField.entries.forEach { field ->
            listOf(field.min, field.max, field.min + 7, field.max - 3).forEach { start ->
                listOf(-MarkerProfileField.COARSE_STEP_FACTOR, -1, +1, +MarkerProfileField.COARSE_STEP_FACTOR)
                    .forEach { delta ->
                        val stepped = field.step(start, delta)
                        val clamped = field.get(field.set(base, stepped).clampedToFirmwareRanges())
                        assertEquals(stepped, clamped, "$field step($start, $delta) is changed by the clamp")
                    }
            }
        }
    }

    // --- visibility ---

    @Test
    fun isShownFor_everyFieldShowsForAnEyeAndTheCustomRampingMode() {
        val shown = MarkerProfileField.entries.filter { it.isShownFor(allShown()) }
        assertEquals(MarkerProfileField.entries, shown)
    }

    @Test
    fun isShownFor_eyeFieldsHideWhenTheEyeIsOff() {
        val blind = profile(fireMode = MarkerFireMode.CUSTOM_RAMPING, eyeMode = MarkerEyeMode.OFF)
        assertFalse(MarkerProfileField.EYE_THRESHOLD.isShownFor(blind))
        assertFalse(MarkerProfileField.EYE_DEBOUNCE.isShownFor(blind))
        assertFalse(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(blind))
        assertTrue(MarkerProfileField.SEAR_SOLENOID_DWELL.isShownFor(blind))
        assertTrue(MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER.isShownFor(blind))
    }

    @Test
    fun isShownFor_cockingTimeoutAlsoNeedsAnEyeGatedFireMode() {
        assertTrue(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(profile(fireMode = MarkerFireMode.SEMI)))
        assertTrue(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(profile(fireMode = MarkerFireMode.FULL_AUTO)))
        assertFalse(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(profile(fireMode = MarkerFireMode.MECHANICAL)))
        assertFalse(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(profile(fireMode = MarkerFireMode.TRIGGER_ONLY)))
    }

    // The two fire mode rules of the board menu (`custom_ramping_off` and `rate_cap_fixed`).

    @Test
    fun isShownFor_rampingFieldsNeedTheCustomRampingMode() {
        MarkerFireMode.entries.forEach { fireMode ->
            val shown = fireMode == MarkerFireMode.CUSTOM_RAMPING
            rampingFields.forEach { field ->
                assertEquals(shown, field.isShownFor(profile(fireMode = fireMode)), "$field in $fireMode")
            }
        }
    }

    @Test
    fun isShownFor_onlyTheNxlModeHidesTheFireRateCap() {
        MarkerFireMode.entries.forEach { fireMode ->
            val shown = fireMode != MarkerFireMode.RAMPING_NXL_2026
            assertEquals(shown, MarkerProfileField.FIRE_RATE_CAP.isShownFor(profile(fireMode = fireMode)), "$fireMode")
        }
    }

    @Test
    fun isShownFor_theNxlModeShowsNoSettingOfItsOwnAndKeepsTheOthers() {
        // The league sets the rules of the mode, so its cap and the ramping values do not show.
        // The eye, timing and trigger fields set how the marker operates, so they stay.
        val league = profile(fireMode = MarkerFireMode.RAMPING_NXL_2026)
        val hidden = MarkerProfileField.entries.filterNot { it.isShownFor(league) }

        assertEquals(listOf(MarkerProfileField.FIRE_RATE_CAP) + rampingFields, hidden)
    }

    @Test
    fun usesCockingTimeout_namesTheModesWithAnEyeGatedCockingPhase() {
        assertEquals(
            setOf(
                MarkerFireMode.SEMI,
                MarkerFireMode.FULL_AUTO,
                MarkerFireMode.RAMPING_NXL_2026,
                MarkerFireMode.CUSTOM_RAMPING,
            ),
            MarkerFireMode.entries.filter { it.usesCockingTimeout }.toSet(),
        )
    }

    @Test
    fun theFireModeRulesNameOneModeEach() {
        assertEquals(listOf(MarkerFireMode.RAMPING_NXL_2026), MarkerFireMode.entries.filter { it.hasFixedRateCap })
        assertEquals(listOf(MarkerFireMode.CUSTOM_RAMPING), MarkerFireMode.entries.filter { it.usesCustomRamping })
    }

    // --- affectsMaxBps agrees with maxBpsForTiming ---

    @Test
    fun affectsMaxBps_namesExactlyTheFieldsThatMoveTheMaxBps() {
        // Every fire mode and eye mode, uncapped. The eye debounce is longer than the lowest
        // timeout, so that the timeout can end the breach-open phase.
        val profiles = MarkerFireMode.entries.flatMap { fireMode ->
            MarkerEyeMode.entries.map { eyeMode ->
                profile(fireMode = fireMode, eyeMode = eyeMode).copy(fireRateCap = 0, eyeDebounce = 100)
            }
        }
        MarkerProfileField.entries.forEach { field ->
            val values = listOf(field.min, field.step(field.min, 1), field.max)
            val moves = profiles.any { base ->
                values.map { field.set(base, it).maxBpsForTiming() }.toSet().size > 1
            }
            assertEquals(field.affectsMaxBps, moves, "$field affectsMaxBps")
        }
    }

    // --- unknown modes ---

    @Test
    fun selectable_leavesOutTheUnknownModes() {
        assertFalse(MarkerFireMode.UNKNOWN in MarkerFireMode.selectable)
        assertFalse(MarkerEyeMode.UNKNOWN in MarkerEyeMode.selectable)
        assertEquals(MarkerFireMode.entries.size - 1, MarkerFireMode.selectable.size)
        assertEquals(MarkerEyeMode.entries.size - 1, MarkerEyeMode.selectable.size)
    }

    @Test
    fun isShownFor_anUnknownEyeModeStillShowsTheEyeFields() {
        val unknownEye = profile(eyeMode = MarkerEyeMode.UNKNOWN)
        assertTrue(MarkerProfileField.EYE_THRESHOLD.isShownFor(unknownEye))
        assertTrue(MarkerProfileField.EYE_DEBOUNCE.isShownFor(unknownEye))
        assertTrue(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(unknownEye))
    }

    @Test
    fun isShownFor_anUnknownFireModeHidesTheRampingFieldsAndTheCockingTimeout() {
        val unknownFire = profile(fireMode = MarkerFireMode.UNKNOWN)
        assertFalse(MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER.isShownFor(unknownFire))
        assertFalse(MarkerProfileField.COCKING_SOLENOID_TIMEOUT.isShownFor(unknownFire))
        assertTrue(MarkerProfileField.SEAR_SOLENOID_DWELL.isShownFor(unknownFire))
    }

    @Test
    fun maxBpsForTiming_anUnknownEyeModeUsesTheEyeDebounce() {
        // The cycle is the delay, the eye debounce and the dwell closed: 100 + 100 + 300 tenths of
        // a millisecond, which is 50 ms, so 20 balls per second.
        val unknownEye = MarkerProfile(
            id = id,
            name = "P",
            eyeMode = MarkerEyeMode.UNKNOWN,
            fireRateCap = 0,
            cockingSolenoidDelay = 100,
            eyeDebounce = 100,
            cockingSolenoidDwellOpen = 900,
            cockingSolenoidDwellClosed = 300,
        )
        assertEquals("20.0", unknownEye.maxBpsForTiming())
    }
}
