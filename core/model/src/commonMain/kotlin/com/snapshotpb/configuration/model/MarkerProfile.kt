package com.snapshotpb.configuration.model

import com.snapshotpb.configuration.common.util.format
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.uuid.Uuid

@Serializable
data class MarkerProfile(
    val id: Uuid,

    /**
     * Name for the given profile, limited to [Device.MAX_PROFILE_NAME_LENGTH] ASCII characters
     * (the board stores at most 10 bytes of it in a fixed-width buffer).
     */
    val name: String,

    /**
     * User preferred fire mode
     */
    val fireMode: MarkerFireMode = MarkerFireMode.SEMI,

    /**
     * A maximum firerate for the marker, in TENTHS of a ball per second (`105` = 10.5 BPS). The
     * cocking-solenoid closed dwell is artificially increased if the fire rate is lower than the
     * calculated cycle time.
     *
     * `0` = uncapped (the firmware uses the timing maximum); any other value is clamped to
     * [RATE_CAP_MIN]–[RATE_CAP_MAX]. A fresh profile defaults to [FIRE_RATE_CAP_DEFAULT] — the
     * proto3 zero-value cannot be relied on because there `0` means uncapped, not the default.
     *
     * The NXL 2026 fire mode does not read this value: the league sets the cap of that mode (see
     * [effectiveFireRateCap]). The editors hide the field in that mode. The stored value stays,
     * a write still sends it, and it applies again when the user selects a different mode.
     *
     * The stored key is `fireRateCapTenths`, not `fireRateCap`. The field counted WHOLE balls per
     * second until the NXL 2026 ramping cap of 10.5 BPS showed that a whole number cannot express
     * a legal rate. Old and new values share one range, so no migration can tell a stored `13`
     * (13 BPS) from a new `13` (1.3 BPS). The key rename retires the old values instead: a record
     * written by an older build carries `fireRateCap`, which `appJson` ignores as an unknown key,
     * so the profile falls back to [FIRE_RATE_CAP_DEFAULT]. Both the local copy and the cached
     * [BoardState] lose the key together, so an app update raises no unsaved badge.
     *
     * The default is a placeholder, not the truth. The next read of a board that holds a different
     * cap surfaces a conflict, and taking the device state adopts the board's own value; taking
     * the local state writes this default back and loses the migrated cap. An edit the user made
     * but never wrote to a board is lost outright, with no badge to announce it. That is the
     * accepted cost of the in-place unit change, which `profile.proto` states the same way.
     */
    @SerialName("fireRateCapTenths")
    val fireRateCap: Int = FIRE_RATE_CAP_DEFAULT,

    /**
     * Eye mode the marker supports if any
     */
    val eyeMode: MarkerEyeMode = MarkerEyeMode.OFF,

    /**
     * Breach eye detection threshold as a percent (0–[EYE_THRESHOLD_MAX]) of the ADC full scale;
     * the eye is considered to see a paintball when the reading is below this threshold. The app
     * never sends `0` — the firmware would substitute its own default ([EYE_THRESHOLD_DEFAULT] %) —
     * so a fresh profile defaults to [EYE_THRESHOLD_DEFAULT] and the UI blocks `0`.
     */
    val eyeThreshold: Int = EYE_THRESHOLD_DEFAULT,

    /**
     * The amount of time in tenths of a millisecond the eye must be within threshold to determine a
     * ball is in the breach. The firmware uses [EYE_DEBOUNCE_DEFAULT] in place of `0`.
     */
    val eyeDebounce: Int = EYE_DEBOUNCE_DEFAULT,

    /**
     * The amount of time in tenths of a millisecond the sear solenoid will be energized during the
     * firing cycle.
     */
    val searSolenoidDwell: Int = 45,

    /**
     * The amount of time in tenths of a millisecond the cocking solenoid waits after a trigger pull
     * before energizing.
     */
    val cockingSolenoidDelay: Int = 60,

    /**
     * The amount of time in tenths of a millisecond the cocking solenoid will energize to open the
     * breach. The firmware ignores this setting when the eye closes the breach (see
     * [eyeClosesBreach]).
     */
    val cockingSolenoidDwellOpen: Int = 50,

    /**
     * The amount of time in tenths of a millisecond the cocking solenoid will stay deenergized to
     * close the breach and prevent the next firing cycle.
     */
    val cockingSolenoidDwellClosed: Int = 300,

    /**
     * The percentage (divided by ten) of increase to the current fire rate once
     * `rampingCustomTriggerPulls` criteria has been met. ie 3 trigger pulls at a multiplier value
     * of 25 (250%) will target a fire rate of 7.5 balls per second. Trigger pulls are counted in a
     * rolling one-second window so fire rate will increase and decrease relative to consistent
     * trigger rate. The editors show the value as a multiple: 25 shows as 2.5× (see
     * [MarkerProfileUnit.TENTHS_MULTIPLE]).
     *
     * This value and the two `rampingCustom*` values below apply in the custom ramping fire mode
     * only ([MarkerFireMode.usesCustomRamping]). No other mode reads them, and the NXL 2026 mode
     * uses the numbers of the rulebook. The editors hide the three fields in each other mode.
     */
    val rampingCustomMultiplier: Int = 15,

    /**
     * The number of trigger pulls in a rolling one-second window that arms the ramp. The ramp
     * stays armed while the window holds that number of pulls.
     */
    val rampingCustomTriggerPulls: Int = 3,

    /**
     * A minimum rate of fire in whole balls per second while the ramp is armed, regardless of
     * trigger pulls in the rolling window.
     */
    val rampingCustomROFMinimum: Int = 3,

    /**
     * The amount of time in tenths of a millisecond the trigger must be pulled to begin a firing
     * cycle. The app never sends `0` (the firmware would substitute its `0 ⇒ 200` fail-safe), so a
     * fresh profile defaults to [TRIGGER_DEBOUNCE_DEFAULT] and the UI blocks `0`.
     */
    val triggerDebounceOn: Int = TRIGGER_DEBOUNCE_DEFAULT,

    /**
     * The amount of time in tenths of a millisecond the trigger must not be sensed before entering
     * the trigger debounce-on cycle. As with [triggerDebounceOn], the app never sends `0`.
     */
    val triggerDebounceOff: Int = TRIGGER_DEBOUNCE_DEFAULT,

    /**
     * Anti-jam fail-open ceiling (tenths of a millisecond): the maximum time the cocking solenoid
     * may stay energized waiting for the eye to confirm a seated ball before the breach is closed
     * on timing instead. Only consulted when an eye sensor (REFLECTIVE/BREAK_BEAM) is active in a
     * fire mode with an eye-gated cocking phase (SEMI / FULL_AUTO / RAMPING_NXL_2026 /
     * CUSTOM_RAMPING).
     *
     * It can never be turned off: the app never sends `0` (the firmware substitutes its
     * `0 ⇒ 5000` = 500 ms default), so a fresh profile defaults to [COCKING_TIMEOUT_DEFAULT] and
     * the UI blocks `0`.
     */
    val cockingSolenoidTimeout: Int = COCKING_TIMEOUT_DEFAULT,

    /**
     * The board slot (0-based) that this profile came from at the last board read or write, or
     * `null` for a profile that the user added after it.
     *
     * This is not a setting. The board keeps a per-profile field that the app does not show
     * (`board_auto_off`), and a write must send it back in the slot of the profile that owns it. A
     * delete moves every later profile down one slot, and a later add can make the list as long as
     * before, so neither the list index nor the id identifies that slot after an edit. This field
     * does. A write puts each profile in the slot of its list index, so the write result sets this
     * field to that index (see [withProfilesInWrittenSlots]).
     *
     * The divergence checks ignore this field ([equalsIgnoringSourceSlot]), because the user cannot
     * see or change it. The Wear sync sends it, because the watch also writes to the board.
     */
    val sourceSlot: Int? = null,
) {
    /**
     * True when the board closes the breach on the eye, not on [cockingSolenoidDwellOpen].
     *
     * The firmware latches this decision when the cocking phase starts
     * (`fire_control_eye_active_gated`). The eye must be REFLECTIVE or BREAK_BEAM, and the fire
     * mode must have an eye-gated cocking phase ([MarkerFireMode.usesCockingTimeout]). An unknown
     * eye mode counts as an eye that is on, as in the editor rules. MECHANICAL and TRIGGER_ONLY
     * never read the eye. An unknown fire mode has no eye-gated cocking phase in the app. The
     * board menu also has a runtime switch that sets the eyes off. The app cannot see that switch,
     * so this value assumes that the eyes are on.
     */
    val eyeClosesBreach: Boolean
        get() = eyeMode != MarkerEyeMode.OFF && fireMode.usesCockingTimeout

    /**
     * The shortest breach-open phase, in tenths of a millisecond. The timing diagram draws this
     * length, and [maxBpsForTiming] uses it.
     *
     * - The eye does not close the breach: the open phase is [cockingSolenoidDwellOpen].
     * - The eye closes the breach: the open phase ends when the eye sees a ball for the full eye
     *   debounce, or at the anti-jam timeout if that comes first. The shortest open phase is thus
     *   the smaller of the two. A ball that is in the breach when the breach opens gives it.
     *
     * The eye values are the values that the firmware uses: it uses [EYE_DEBOUNCE_DEFAULT] in
     * place of an eye debounce of `0`, and [COCKING_TIMEOUT_DEFAULT] in place of a timeout of `0`.
     */
    val cockingOpenTenthsMs: Int
        get() = if (eyeClosesBreach) {
            min(effectiveEyeDebounce, effectiveCockingTimeout)
        } else {
            cockingSolenoidDwellOpen
        }

    /**
     * The longest breach-open phase, in tenths of a millisecond. When the eye closes the breach,
     * this is the anti-jam timeout: with no ball, the board closes the breach at the timeout. In
     * the other cases it is the same as [cockingOpenTenthsMs].
     */
    val cockingOpenLimitTenthsMs: Int
        get() = if (eyeClosesBreach) effectiveCockingTimeout else cockingSolenoidDwellOpen

    /**
     * The firing cycle, in tenths of a millisecond: the cocking delay, the shortest breach-open
     * phase ([cockingOpenTenthsMs]) and the closed dwell. The firmware starts the cycle when the
     * trigger debounce-on time ends, so the cycle does not include that time.
     */
    val cycleTenthsMs: Int
        get() = cockingSolenoidDelay + cockingOpenTenthsMs + cockingSolenoidDwellClosed

    /**
     * The fire-rate cap that the board applies, in tenths of a ball per second. `0` = uncapped.
     *
     * In the NXL 2026 fire mode the board uses the league limit ([NXL_2026_RATE_CAP]) and does not
     * read [fireRateCap]: a stored cap on each side of the limit gives the same rate. In each
     * other mode this is [fireRateCap].
     */
    val effectiveFireRateCap: Int
        get() = if (fireMode.hasFixedRateCap) NXL_2026_RATE_CAP else fireRateCap

    private val effectiveEyeDebounce: Int
        get() = if (eyeDebounce <= 0) EYE_DEBOUNCE_DEFAULT else eyeDebounce

    private val effectiveCockingTimeout: Int
        get() = if (cockingSolenoidTimeout <= 0) COCKING_TIMEOUT_DEFAULT else cockingSolenoidTimeout

    /**
     * True when [other] has the same id and the same settings. [sourceSlot] is not compared,
     * because it is not a value the user can see or change.
     */
    fun equalsIgnoringSourceSlot(other: MarkerProfile?): Boolean =
        other != null && copy(sourceSlot = null) == other.copy(sourceSlot = null)

    /**
     * The timing-limited maximum balls-per-second for this profile, clamped to
     * [effectiveFireRateCap] (tenths of a ball per second), formatted to one decimal place.
     *
     * The cycle is [cycleTenthsMs], which has the same phases that `ProfileTimingDiagram` draws.
     * When the eye closes the breach, the value is the best case: it assumes that a ball is in the
     * breach when the breach opens. A slow feed makes the real rate lower.
     *
     * Because the phases are in tenths of a millisecond, one second is [TENTHS_MS_PER_SECOND]
     * units, and the timing-limited rate is `TENTHS_MS_PER_SECOND / cycleTenthsMs` (bps), computed
     * before clamping to the cap, which is scaled from tenths back to whole bps for the compare.
     * The cap is the league limit in the NXL 2026 fire mode, and the cap of the user in each other
     * mode.
     */
    fun maxBpsForTiming(): String {
        val cycle = cycleTenthsMs
        val timingBps = if (cycle > 0) TENTHS_MS_PER_SECOND / cycle else 0.0
        // A cap <= 0 means "uncapped": show the timing-limited rate rather than a literal 0.
        val capTenths = effectiveFireRateCap
        val cap = if (capTenths <= 0) timingBps else capTenths / TENTHS_PER_BALL
        return min(timingBps, cap).format(1)
    }

    /**
     * Returns a copy with every field bounded exactly as the firmware's write chokepoint would
     * bound it, so the app never sends a value the board would silently change. Applied at the
     * board-write boundary (see [Device.clampedToFirmwareRanges]).
     *
     * The four "`0` ⇒ firmware default" fields ([eyeThreshold], [triggerDebounceOn],
     * [triggerDebounceOff], [cockingSolenoidTimeout]) map a non-positive value to the app's
     * concrete default instead of emitting the sentinel. [fireRateCap] keeps `0` as its real
     * "uncapped" value. Every other field is coerced into range, with negatives floored to `0`
     * (guards legacy/import/watch-sync values before the unsigned wire encoding).
     */
    fun clampedToFirmwareRanges(): MarkerProfile = copy(
        name = name.limitProfileName(),
        fireRateCap = if (fireRateCap <= 0) 0 else fireRateCap.coerceIn(RATE_CAP_MIN, RATE_CAP_MAX),
        eyeThreshold = if (eyeThreshold <= 0) EYE_THRESHOLD_DEFAULT else eyeThreshold.coerceIn(0, EYE_THRESHOLD_MAX),
        eyeDebounce = eyeDebounce.coerceIn(0, TENTHS_MS_MAX),
        searSolenoidDwell = searSolenoidDwell.coerceIn(0, TENTHS_MS_MAX),
        cockingSolenoidDelay = cockingSolenoidDelay.coerceIn(0, TENTHS_MS_MAX),
        cockingSolenoidDwellOpen = cockingSolenoidDwellOpen.coerceIn(0, TENTHS_MS_MAX),
        cockingSolenoidDwellClosed = cockingSolenoidDwellClosed.coerceIn(0, TENTHS_MS_MAX),
        rampingCustomMultiplier = rampingCustomMultiplier.coerceIn(0, TENTHS_MS_MAX),
        rampingCustomTriggerPulls = rampingCustomTriggerPulls.coerceIn(0, COUNT_MAX),
        rampingCustomROFMinimum = rampingCustomROFMinimum.coerceIn(0, COUNT_MAX),
        triggerDebounceOn = if (triggerDebounceOn <= 0) TRIGGER_DEBOUNCE_DEFAULT else triggerDebounceOn.coerceIn(0, TENTHS_MS_MAX),
        triggerDebounceOff = if (triggerDebounceOff <= 0) TRIGGER_DEBOUNCE_DEFAULT else triggerDebounceOff.coerceIn(0, TENTHS_MS_MAX),
        cockingSolenoidTimeout = if (cockingSolenoidTimeout <= 0) COCKING_TIMEOUT_DEFAULT else cockingSolenoidTimeout.coerceIn(0, TENTHS_MS_MAX),
    )

    companion object {
        /** Tenths of a millisecond in one second (1000 ms × 10), used to convert a cycle time to bps. */
        private const val TENTHS_MS_PER_SECOND = 10000.0

        /** A fire-rate cap counts tenths of a ball per second; divide by this to get whole bps. */
        private const val TENTHS_PER_BALL = 10.0
    }
}
