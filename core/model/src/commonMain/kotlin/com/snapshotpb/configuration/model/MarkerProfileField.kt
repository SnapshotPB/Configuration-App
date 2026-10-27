package com.snapshotpb.configuration.model

/** The unit of the value a [MarkerProfileField] stores. */
enum class MarkerProfileUnit {
    /** Tenths of a millisecond. */
    TENTHS_MS,

    /** Whole percent. */
    PERCENT,

    /** Tenths of a ball per second. */
    TENTHS_BPS,

    /** Whole balls per second. */
    BPS,

    /**
     * Tenths of a multiple. The protocol gives this value as a percent divided by ten, so `25` is
     * 250 percent, which is 2.5 times. An editor shows the multiple with one decimal.
     */
    TENTHS_MULTIPLE,

    /** A plain count. */
    COUNT,
}

/** The section of the profile editor that shows a [MarkerProfileField]. */
enum class MarkerProfileGroup {
    BASIC,
    EYE,
    TIMING,
    TRIGGER,
    RAMPING,
}

/**
 * One numeric field of a [MarkerProfile], with its unit, its limits and its visibility rule.
 *
 * The watch apps use this table for a stepper editor. It is the one source of truth: the Zepp
 * app mirrors it by hand with the same entry names, so a change here needs the same change there.
 * The fire mode and the eye mode are not in the table, because they are enum fields with their
 * own selection screens (and bolt mode lives at the device level).
 *
 * [min] and [max] bound the range of values the firmware keeps unchanged, so a stepper never
 * visits a value the board would replace (see [MarkerProfile.clampedToFirmwareRanges]).
 * [FIRE_RATE_CAP] is the one field with a gap in its range: `0` means uncapped and the lowest
 * cap is [RATE_CAP_MIN], so [step] jumps across the values between them.
 *
 * [affectsMaxBps] is true when a change to the field moves the max BPS that
 * [MarkerProfile.maxBpsForTiming] reports, so an editor shows the max BPS line only for these
 * fields. The fixture `core/model/src/jvmTest/resources/profile-fields.json` holds a copy of the
 * table, and a JVM test compares the two, so a change here also needs a change to the fixture.
 */
enum class MarkerProfileField(
    val unit: MarkerProfileUnit,
    val min: Int,
    val max: Int,
    val group: MarkerProfileGroup,
    val affectsMaxBps: Boolean,
    private val read: (MarkerProfile) -> Int,
    private val write: (MarkerProfile, Int) -> MarkerProfile,
    private val shownFor: (MarkerProfile, MarkerBoltMode) -> Boolean = { _, _ -> true },
) {
    FIRE_RATE_CAP(
        unit = MarkerProfileUnit.TENTHS_BPS,
        min = 0,
        max = RATE_CAP_MAX,
        group = MarkerProfileGroup.BASIC,
        affectsMaxBps = true,
        read = { it.fireRateCap },
        write = { profile, value -> profile.copy(fireRateCap = value) },
        shownFor = { profile, _ -> !profile.fireMode.hasFixedRateCap },
    ),
    EYE_THRESHOLD(
        unit = MarkerProfileUnit.PERCENT,
        min = 1,
        max = EYE_THRESHOLD_MAX,
        group = MarkerProfileGroup.EYE,
        affectsMaxBps = false,
        read = { it.eyeThreshold },
        write = { profile, value -> profile.copy(eyeThreshold = value) },
        shownFor = { profile, _ -> profile.usesEye },
    ),
    EYE_DEBOUNCE(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.EYE,
        affectsMaxBps = true,
        read = { it.eyeDebounce },
        write = { profile, value -> profile.copy(eyeDebounce = value) },
        shownFor = { profile, _ -> profile.usesEye },
    ),
    COCKING_SOLENOID_TIMEOUT(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 1,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.EYE,
        // A timeout shorter than the eye debounce ends the breach-open phase first.
        affectsMaxBps = true,
        read = { it.cockingSolenoidTimeout },
        write = { profile, value -> profile.copy(cockingSolenoidTimeout = value) },
        shownFor = { profile, _ -> profile.eyeClosesBreach },
    ),
    SEAR_SOLENOID_DWELL(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TIMING,
        // In open-bolt mode, searSolenoidDelay + searSolenoidDwell can bound cycleTenthsMs.
        affectsMaxBps = true,
        read = { it.searSolenoidDwell },
        write = { profile, value -> profile.copy(searSolenoidDwell = value) },
    ),
    SEAR_SOLENOID_DELAY(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TIMING,
        affectsMaxBps = true,
        read = { it.searSolenoidDelay },
        write = { profile, value -> profile.copy(searSolenoidDelay = value) },
        shownFor = { profile, boltMode -> profile.usesOpenBolt(boltMode) },
    ),
    COCKING_SOLENOID_DELAY(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TIMING,
        affectsMaxBps = true,
        read = { it.cockingSolenoidDelay },
        write = { profile, value -> profile.copy(cockingSolenoidDelay = value) },
        shownFor = { profile, boltMode -> !profile.usesOpenBolt(boltMode) },
    ),
    COCKING_SOLENOID_DWELL_OPEN(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TIMING,
        affectsMaxBps = true,
        read = { it.cockingSolenoidDwellOpen },
        write = { profile, value -> profile.copy(cockingSolenoidDwellOpen = value) },
    ),
    COCKING_SOLENOID_DWELL_CLOSED(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TIMING,
        affectsMaxBps = true,
        read = { it.cockingSolenoidDwellClosed },
        write = { profile, value -> profile.copy(cockingSolenoidDwellClosed = value) },
    ),
    TRIGGER_DEBOUNCE_ON(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 1,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TRIGGER,
        affectsMaxBps = false,
        read = { it.triggerDebounceOn },
        write = { profile, value -> profile.copy(triggerDebounceOn = value) },
    ),
    TRIGGER_DEBOUNCE_OFF(
        unit = MarkerProfileUnit.TENTHS_MS,
        min = 1,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.TRIGGER,
        affectsMaxBps = false,
        read = { it.triggerDebounceOff },
        write = { profile, value -> profile.copy(triggerDebounceOff = value) },
    ),
    RAMPING_CUSTOM_MULTIPLIER(
        unit = MarkerProfileUnit.TENTHS_MULTIPLE,
        min = 0,
        max = TENTHS_MS_MAX,
        group = MarkerProfileGroup.RAMPING,
        affectsMaxBps = false,
        read = { it.rampingCustomMultiplier },
        write = { profile, value -> profile.copy(rampingCustomMultiplier = value) },
        shownFor = { profile, _ -> profile.fireMode.usesCustomRamping },
    ),
    RAMPING_CUSTOM_TRIGGER_PULLS(
        unit = MarkerProfileUnit.COUNT,
        min = 0,
        max = COUNT_MAX,
        group = MarkerProfileGroup.RAMPING,
        affectsMaxBps = false,
        read = { it.rampingCustomTriggerPulls },
        write = { profile, value -> profile.copy(rampingCustomTriggerPulls = value) },
        shownFor = { profile, _ -> profile.fireMode.usesCustomRamping },
    ),
    RAMPING_CUSTOM_ROF_MINIMUM(
        unit = MarkerProfileUnit.BPS,
        min = 0,
        max = COUNT_MAX,
        group = MarkerProfileGroup.RAMPING,
        affectsMaxBps = false,
        read = { it.rampingCustomROFMinimum },
        write = { profile, value -> profile.copy(rampingCustomROFMinimum = value) },
        shownFor = { profile, _ -> profile.fireMode.usesCustomRamping },
    ),
    ;

    /** The stored value of this field in [profile]. */
    fun get(profile: MarkerProfile): Int = read(profile)

    /**
     * A copy of [profile] with this field set to [value]. The value is stored as given. The
     * store boundary applies [MarkerProfile.clampedToFirmwareRanges] before it keeps the profile.
     */
    fun set(profile: MarkerProfile, value: Int): MarkerProfile = write(profile, value)

    /**
     * Whether an editor shows this field for [profile] under the device's [boltMode]. The eye
     * fields need an eye mode, and the cocking timeout also needs a fire mode with an eye-gated
     * cocking phase.
     *
     * The fire mode and bolt mode rules are the rules of the board menu. The ramping fields show
     * only in the custom ramping mode ([MarkerFireMode.usesCustomRamping]), because no other mode
     * reads them. The fire-rate cap does not show in the NXL 2026 mode
     * ([MarkerFireMode.hasFixedRateCap]), because the league sets the cap of that mode.
     * [SEAR_SOLENOID_DELAY] shows when [MarkerProfile.usesOpenBolt] is true and
     * [COCKING_SOLENOID_DELAY] shows when it is false. A hidden field keeps its stored value, and a
     * write still sends it.
     */
    fun isShownFor(
        profile: MarkerProfile,
        boltMode: MarkerBoltMode = MarkerBoltMode.CLOSED,
    ): Boolean = shownFor(profile, boltMode)

    /**
     * The value after a step of [delta] from [value], held inside [min] and [max].
     *
     * A stepper rule, not a wire rule: [FIRE_RATE_CAP] steps from [RATE_CAP_MIN] down to `0`
     * (uncapped) and from `0` up to [RATE_CAP_MIN]. The firmware accepts the values between them
     * and raises each one to [RATE_CAP_MIN], so a stepper has no reason to visit them. A step that
     * starts above [RATE_CAP_MIN] stops at [RATE_CAP_MIN] first. A zero [delta] is no step, so
     * it only applies the limits.
     */
    fun step(value: Int, delta: Int): Int {
        val target = value + delta
        if (this != FIRE_RATE_CAP || delta == 0) return target.coerceIn(min, max)
        return when {
            target <= 0 -> 0
            target >= RATE_CAP_MIN -> target.coerceAtMost(max)
            // The target sits in the gap between uncapped and the lowest cap.
            delta < 0 && value <= RATE_CAP_MIN -> 0
            else -> RATE_CAP_MIN
        }
    }

    companion object {
        /**
         * The coarse step of a stepper, as a multiple of the fine step. The fine step is always
         * one stored unit, so a stepper can reach every value the phone can type. This ratio is a
         * first guess that a later change can tune by feel.
         */
        const val COARSE_STEP_FACTOR = 5
    }
}

/**
 * Whether the profile uses an eye sensor, which gates the eye fields of the editor. An unknown eye
 * mode counts as a sensor, so the editor still shows the eye values that the board holds.
 */
private val MarkerProfile.usesEye: Boolean
    get() = eyeMode != MarkerEyeMode.OFF
