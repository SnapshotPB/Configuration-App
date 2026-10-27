package com.snapshotpb.configuration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fire modes the marker board supports. Mirrors the protocol's `AutocockerFireMode`, with the same
 * entry names.
 */
@Serializable
enum class MarkerFireMode {
    MECHANICAL,
    SEMI,
    TRIGGER_ONLY,
    FULL_AUTO,

    /**
     * Ramping with the rules of the NXL 2026 rulebook. The user cannot adjust a rule of this mode:
     * the board does not read the fire-rate cap of the profile ([hasFixedRateCap]), and it does not
     * read the `rampingCustom*` values.
     *
     * The stored name is `RAMPING_CUSTOM`, not `RAMPING_NXL_2026`. This was the one ramping mode
     * of the app before the protocol had a custom ramping mode, and its name then was
     * `RAMPING_CUSTOM`. A stored profile and a Wear peer on an older build both use that name for
     * this mode, so the name must keep this meaning. With a new stored name, an older build reads
     * the mode as the property default (`SEMI`).
     */
    @SerialName("RAMPING_CUSTOM")
    RAMPING_NXL_2026,

    /**
     * Ramping with rules that the user sets. The three `rampingCustom*` values of the profile
     * apply in this mode only ([usesCustomRamping]), and the fire-rate cap is the upper limit of
     * the rate. Do not confuse the stored name `CUSTOM_RAMPING` with the stored name of
     * [RAMPING_NXL_2026].
     */
    CUSTOM_RAMPING,

    /**
     * The board reported a fire mode that this app does not know, or no fire mode (the protocol
     * `UNSPECIFIED`). An editor shows it as unknown and does not offer it as a choice. A write
     * sends the board's own number back, so the board keeps its mode until the user selects one.
     * It is not the custom ramping mode and has no eye-gated cocking phase, so the editors hide
     * the ramping fields and the cocking timeout for it. The board keeps those values, because a
     * write sends every numeric field. It is not the NXL 2026 mode, so the editors show the
     * fire-rate cap, and the max BPS uses the cap of the profile.
     */
    UNKNOWN,
    ;

    /**
     * Whether this fire mode has an eye-gated cocking-open phase, i.e. one where
     * `cocking_solenoid_timeout` (the anti-jam fail-open ceiling) is consulted by the firmware. Only
     * SEMI, FULL_AUTO, RAMPING_NXL_2026 and CUSTOM_RAMPING do — MECHANICAL holds the breach open
     * via the trigger and TRIGGER_ONLY has no cocking phase — so the timeout editor is hidden for
     * the others.
     */
    val usesCockingTimeout: Boolean
        get() = this == SEMI || this == FULL_AUTO || this == RAMPING_NXL_2026 || this == CUSTOM_RAMPING

    /**
     * Whether this fire mode runs a timed/eye-gated cocking cycle where [MarkerBoltMode] applies.
     * Only [SEMI], [FULL_AUTO], [RAMPING_NXL_2026], and [CUSTOM_RAMPING] do — [MECHANICAL] holds
     * the breach open via the trigger and [TRIGGER_ONLY] has no cocking phase — so the bolt-mode
     * and sear-delay editors are hidden for the others.
     */
    val usesBoltMode: Boolean
        get() = this == SEMI || this == FULL_AUTO || this == RAMPING_NXL_2026 || this == CUSTOM_RAMPING

    /**
     * Whether the league sets the rate cap of this fire mode. Only [RAMPING_NXL_2026] has such a
     * cap ([NXL_2026_RATE_CAP]). The board does not read the fire-rate cap of the profile in that
     * mode, so the editors hide the cap. The stored cap stays, and it applies again in a different
     * mode. This is the `rate_cap_fixed` rule of the board menu.
     */
    val hasFixedRateCap: Boolean
        get() = this == RAMPING_NXL_2026

    /**
     * Whether this fire mode reads the three `rampingCustom*` values. Only [CUSTOM_RAMPING] does,
     * so the editors hide the ramping fields for each other mode. This is the opposite of the
     * `custom_ramping_off` rule of the board menu.
     */
    val usesCustomRamping: Boolean
        get() = this == CUSTOM_RAMPING

    companion object {
        /** The fire modes that an editor offers. [UNKNOWN] is not a mode that the user can select. */
        val selectable: List<MarkerFireMode> = entries.filter { it != UNKNOWN }
    }
}
