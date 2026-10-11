package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MarkerProfileTest {

    private val id = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun profile(
        name: String = "Semi",
        fireRateCap: Int = FIRE_RATE_CAP_DEFAULT,
        eyeThreshold: Int = EYE_THRESHOLD_DEFAULT,
        eyeDebounce: Int = 20,
        triggerDebounceOn: Int = TRIGGER_DEBOUNCE_DEFAULT,
        triggerDebounceOff: Int = TRIGGER_DEBOUNCE_DEFAULT,
        cockingSolenoidTimeout: Int = COCKING_TIMEOUT_DEFAULT,
        rampingCustomTriggerPulls: Int = 3,
        rampingCustomROFMinimum: Int = 3,
        rampingCustomMultiplier: Int = 15,
    ) = MarkerProfile(
        id = id,
        name = name,
        fireRateCap = fireRateCap,
        eyeThreshold = eyeThreshold,
        eyeDebounce = eyeDebounce,
        triggerDebounceOn = triggerDebounceOn,
        triggerDebounceOff = triggerDebounceOff,
        cockingSolenoidTimeout = cockingSolenoidTimeout,
        rampingCustomTriggerPulls = rampingCustomTriggerPulls,
        rampingCustomROFMinimum = rampingCustomROFMinimum,
        rampingCustomMultiplier = rampingCustomMultiplier,
    )

    // --- fire_rate_cap (tenths of a ball per second): 0 is a kept "uncapped" sentinel;
    // otherwise clamp RATE_CAP_MIN..RATE_CAP_MAX ---

    @Test
    fun clamp_fireRateCap_keepsZeroAsUncapped() {
        assertEquals(0, profile(fireRateCap = 0).clampedToFirmwareRanges().fireRateCap)
    }

    @Test
    fun clamp_fireRateCap_boundsAboveAndBelowAndFloorsNegative() {
        assertEquals(RATE_CAP_MAX, profile(fireRateCap = 2_000).clampedToFirmwareRanges().fireRateCap)
        // Below the minimum, but still a real cap request, so it rises to the minimum.
        assertEquals(RATE_CAP_MIN, profile(fireRateCap = 3).clampedToFirmwareRanges().fireRateCap)
        val inRange = FIRE_RATE_CAP_DEFAULT
        assertEquals(inRange, profile(fireRateCap = inRange).clampedToFirmwareRanges().fireRateCap)
        // A negative is not a real "uncapped" request; it collapses to the 0 sentinel.
        assertEquals(0, profile(fireRateCap = -3).clampedToFirmwareRanges().fireRateCap)
    }

    @Test
    fun fireRateCapDefaultIsTheNxlRampingLimit() {
        // The NXL 2026 rulebook caps ramping at 10.5 BPS, which is why the field counts tenths.
        assertEquals(105, NXL_2026_RATE_CAP)
        assertEquals(NXL_2026_RATE_CAP, FIRE_RATE_CAP_DEFAULT)
    }

    @Test
    fun clamp_keepsTheStoredCapAndRampingValuesInTheNxlMode() {
        // The NXL 2026 mode does not read these values, but the board keeps them for the next
        // mode. The write boundary must not replace them.
        val league = profile(fireRateCap = 150, rampingCustomTriggerPulls = 4, rampingCustomMultiplier = 25)
            .copy(fireMode = MarkerFireMode.RAMPING_NXL_2026)

        assertEquals(league, league.clampedToFirmwareRanges())
    }

    // --- "0 => firmware default" fields: never emit 0 ---

    @Test
    fun clamp_zeroSentinelFieldsAdoptConcreteDefaults() {
        val clamped = profile(
            eyeThreshold = 0,
            triggerDebounceOn = 0,
            triggerDebounceOff = 0,
            cockingSolenoidTimeout = 0,
        ).clampedToFirmwareRanges()
        assertEquals(EYE_THRESHOLD_DEFAULT, clamped.eyeThreshold)
        assertEquals(TRIGGER_DEBOUNCE_DEFAULT, clamped.triggerDebounceOn)
        assertEquals(TRIGGER_DEBOUNCE_DEFAULT, clamped.triggerDebounceOff)
        assertEquals(COCKING_TIMEOUT_DEFAULT, clamped.cockingSolenoidTimeout)
    }

    @Test
    fun clamp_zeroSentinelFieldsAlsoRecoverFromNegatives() {
        val clamped = profile(
            eyeThreshold = -10,
            triggerDebounceOn = -1,
            cockingSolenoidTimeout = -99,
        ).clampedToFirmwareRanges()
        assertEquals(EYE_THRESHOLD_DEFAULT, clamped.eyeThreshold)
        assertEquals(TRIGGER_DEBOUNCE_DEFAULT, clamped.triggerDebounceOn)
        assertEquals(COCKING_TIMEOUT_DEFAULT, clamped.cockingSolenoidTimeout)
    }

    @Test
    fun clamp_eyeThreshold_boundsToPercent() {
        assertEquals(EYE_THRESHOLD_MAX, profile(eyeThreshold = 400).clampedToFirmwareRanges().eyeThreshold)
        assertEquals(73, profile(eyeThreshold = 73).clampedToFirmwareRanges().eyeThreshold)
    }

    // --- timing / count fields ---

    @Test
    fun clamp_timingFieldsBoundToTenthsMax() {
        val clamped = profile(eyeDebounce = 200_000).clampedToFirmwareRanges()
        assertEquals(TENTHS_MS_MAX, clamped.eyeDebounce)
    }

    @Test
    fun clamp_countFieldsBoundToCountMax() {
        val clamped = profile(
            rampingCustomTriggerPulls = 50_000,
            rampingCustomROFMinimum = 50_000,
            rampingCustomMultiplier = 200_000,
        ).clampedToFirmwareRanges()
        assertEquals(COUNT_MAX, clamped.rampingCustomTriggerPulls)
        assertEquals(COUNT_MAX, clamped.rampingCustomROFMinimum)
        assertEquals(TENTHS_MS_MAX, clamped.rampingCustomMultiplier)
    }

    @Test
    fun clamp_name_filtersAsciiAndTruncates() {
        assertEquals("cafMarker-", profile(name = "caféMarker-XL").clampedToFirmwareRanges().name)
    }

    @Test
    fun clamp_isIdempotent() {
        val once = profile(fireRateCap = 2_000, eyeThreshold = 0, eyeDebounce = 200_000)
            .clampedToFirmwareRanges()
        assertEquals(once, once.clampedToFirmwareRanges())
    }

    // --- maxBpsForTiming: fireRateCap == 0 (uncapped) shows the timing rate, not 0.0 ---

    @Test
    fun maxBpsForTiming_uncappedShowsTimingRateNotZero() {
        // Eye OFF: cycle = delay(60) + open(50) + closed(300) = 410 tenths-ms; 10000/410 ≈ 24.4.
        val uncapped = MarkerProfile(
            id = id,
            name = "P",
            eyeMode = MarkerEyeMode.OFF,
            fireRateCap = 0,
            cockingSolenoidDelay = 60,
            cockingSolenoidDwellOpen = 50,
            cockingSolenoidDwellClosed = 300,
        )
        assertEquals("24.4", uncapped.maxBpsForTiming())
    }

    @Test
    fun maxBpsForTiming_cappedClampsToCap() {
        // The cap is 100 tenths of a ball per second, so the display reads 10.0 BPS, not 100.0.
        val capped = MarkerProfile(
            id = id,
            name = "P",
            eyeMode = MarkerEyeMode.OFF,
            fireRateCap = 100,
            cockingSolenoidDelay = 60,
            cockingSolenoidDwellOpen = 50,
            cockingSolenoidDwellClosed = 300,
        )
        assertEquals("10.0", capped.maxBpsForTiming())
    }

    @Test
    fun maxBpsForTiming_capScalesFromTenths() {
        // 105 tenths is 10.5 BPS; a whole-number reading would show 105.0 instead.
        val capped = MarkerProfile(
            id = id,
            name = "P",
            eyeMode = MarkerEyeMode.OFF,
            fireRateCap = FIRE_RATE_CAP_DEFAULT,
            cockingSolenoidDelay = 60,
            cockingSolenoidDwellOpen = 50,
            cockingSolenoidDwellClosed = 300,
        )
        assertEquals("10.5", capped.maxBpsForTiming())
    }

    // --- The NXL 2026 mode: the league sets the cap, and the board does not read the profile cap ---

    @Test
    fun maxBpsForTiming_theNxlModeUsesTheLeagueCapForEachProfileCap() {
        // Timing rate 24.4 BPS. A stored cap of uncapped, below the limit, at the limit and above
        // the limit all give the league limit, because the board does not read the stored cap.
        listOf(0, 50, NXL_2026_RATE_CAP, 150, RATE_CAP_MAX).forEach { storedCap ->
            val league = timing(MarkerEyeMode.OFF, MarkerFireMode.RAMPING_NXL_2026).copy(fireRateCap = storedCap)
            assertEquals(NXL_2026_RATE_CAP, league.effectiveFireRateCap, "stored cap $storedCap")
            assertEquals("10.5", league.maxBpsForTiming(), "stored cap $storedCap")
        }
    }

    @Test
    fun maxBpsForTiming_theNxlModeStillShowsATimingRateBelowTheLeagueCap() {
        // 60 + 50 + 1890 = 2000 tenths of a ms; 10000 / 2000 = 5.0 BPS. The league cap is a
        // limit, so it does not make a slow marker faster.
        val slow = timing(MarkerEyeMode.OFF, MarkerFireMode.RAMPING_NXL_2026)
            .copy(cockingSolenoidDwellClosed = 1890)
        assertEquals("5.0", slow.maxBpsForTiming())
    }

    @Test
    fun effectiveFireRateCap_isTheProfileCapInEachOtherMode() {
        (MarkerFireMode.entries - MarkerFireMode.RAMPING_NXL_2026).forEach { fireMode ->
            val capped = timing(MarkerEyeMode.OFF, fireMode).copy(fireRateCap = 150)
            assertEquals(150, capped.effectiveFireRateCap, "$fireMode")
            assertEquals("15.0", capped.maxBpsForTiming(), "$fireMode")
        }
    }

    // --- The breach-open phase for each eye mode (firmware fc_build_entry_effects, FC_ST_COCKING) ---

    /** Uncapped, so the max BPS shows the timing rate. Delay 6.0 ms, open 5.0 ms, closed 30.0 ms. */
    private fun timing(
        eyeMode: MarkerEyeMode,
        fireMode: MarkerFireMode = MarkerFireMode.SEMI,
        eyeDebounce: Int = 20,
        cockingSolenoidTimeout: Int = COCKING_TIMEOUT_DEFAULT,
    ) = MarkerProfile(
        id = id,
        name = "P",
        fireMode = fireMode,
        eyeMode = eyeMode,
        fireRateCap = 0,
        eyeDebounce = eyeDebounce,
        cockingSolenoidDelay = 60,
        cockingSolenoidDwellOpen = 50,
        cockingSolenoidDwellClosed = 300,
        cockingSolenoidTimeout = cockingSolenoidTimeout,
    )

    private val eyeModesWithASensor = listOf(MarkerEyeMode.REFLECTIVE, MarkerEyeMode.BREAK_BEAM)

    /** The max BPS of an uncapped profile: the timing rate, or the league cap in the NXL 2026 mode. */
    private fun uncappedMaxBps(fireMode: MarkerFireMode, timingRate: String): String =
        if (fireMode == MarkerFireMode.RAMPING_NXL_2026) "10.5" else timingRate

    @Test
    fun eyeOff_theDwellOpenIsTheOpenPhase() {
        MarkerFireMode.entries.forEach { fireMode ->
            val profile = timing(MarkerEyeMode.OFF, fireMode)
            assertEquals(false, profile.eyeClosesBreach, "$fireMode")
            assertEquals(50, profile.cockingOpenTenthsMs, "$fireMode")
            assertEquals(50, profile.cockingOpenLimitTenthsMs, "$fireMode")
            assertEquals(410, profile.cycleTenthsMs, "$fireMode")
            assertEquals(uncappedMaxBps(fireMode, "24.4"), profile.maxBpsForTiming(), "$fireMode")
        }
    }

    @Test
    fun eyeOn_eyeGatedFireMode_theEyeDebounceIsTheShortestOpenPhase() {
        eyeModesWithASensor.forEach { eyeMode ->
            listOf(
                MarkerFireMode.SEMI,
                MarkerFireMode.FULL_AUTO,
                MarkerFireMode.RAMPING_NXL_2026,
                MarkerFireMode.CUSTOM_RAMPING,
            ).forEach { fireMode ->
                val profile = timing(eyeMode, fireMode)
                assertEquals(true, profile.eyeClosesBreach, "$eyeMode $fireMode")
                // The firmware ignores the dwell open (5.0 ms) here.
                assertEquals(20, profile.cockingOpenTenthsMs, "$eyeMode $fireMode")
                // With no ball, the anti-jam timeout closes the breach.
                assertEquals(COCKING_TIMEOUT_DEFAULT, profile.cockingOpenLimitTenthsMs, "$eyeMode $fireMode")
                // 60 + 20 + 300 = 380 tenths of a ms; 10000 / 380 = 26.3 BPS.
                assertEquals(380, profile.cycleTenthsMs, "$eyeMode $fireMode")
                assertEquals(uncappedMaxBps(fireMode, "26.3"), profile.maxBpsForTiming(), "$eyeMode $fireMode")
            }
        }
    }

    @Test
    fun eyeOn_modesWithNoEyeGatedCocking_keepTheDwellOpen() {
        // MECHANICAL holds the breach open on the trigger and TRIGGER_ONLY has no cocking phase;
        // neither reads the eye, so the eye must not change their cycle.
        eyeModesWithASensor.forEach { eyeMode ->
            listOf(MarkerFireMode.MECHANICAL, MarkerFireMode.TRIGGER_ONLY).forEach { fireMode ->
                val profile = timing(eyeMode, fireMode)
                assertEquals(false, profile.eyeClosesBreach, "$eyeMode $fireMode")
                assertEquals(50, profile.cockingOpenTenthsMs, "$eyeMode $fireMode")
                assertEquals(410, profile.cycleTenthsMs, "$eyeMode $fireMode")
            }
        }
    }

    @Test
    fun eyeOn_zeroEyeDebounce_usesTheFirmwareDefault() {
        // The firmware uses 2.0 ms in place of 0 (fc_eye_debounce_tenths_ms).
        val profile = timing(MarkerEyeMode.REFLECTIVE, eyeDebounce = 0)
        assertEquals(EYE_DEBOUNCE_DEFAULT, profile.cockingOpenTenthsMs)
    }

    @Test
    fun eyeOn_zeroTimeout_usesTheFirmwareDefault() {
        // The firmware uses 500 ms in place of 0 (fc_eye_cocking_timeout_tenths_ms).
        val profile = timing(MarkerEyeMode.BREAK_BEAM, cockingSolenoidTimeout = 0)
        assertEquals(COCKING_TIMEOUT_DEFAULT, profile.cockingOpenLimitTenthsMs)
    }

    @Test
    fun eyeOn_timeoutShorterThanTheDebounce_theTimeoutEndsTheOpenPhase() {
        // The eye cannot confirm a ball before the timeout, so the breach always closes on it.
        val profile = timing(MarkerEyeMode.REFLECTIVE, eyeDebounce = 100, cockingSolenoidTimeout = 30)
        assertEquals(30, profile.cockingOpenTenthsMs)
        assertEquals(30, profile.cockingOpenLimitTenthsMs)
        assertEquals(390, profile.cycleTenthsMs)
    }

    @Test
    fun eyeOn_theCapStillLimitsTheRate() {
        val capped = timing(MarkerEyeMode.REFLECTIVE).copy(fireRateCap = FIRE_RATE_CAP_DEFAULT)
        assertEquals("10.5", capped.maxBpsForTiming())
    }
}
