package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/** Eye (ball-detection) sensor modes. Mirrors the protocol's `EyeMode`. */
@Serializable
enum class MarkerEyeMode {
    OFF,
    REFLECTIVE,
    BREAK_BEAM,

    /**
     * The board reported an eye mode that this app does not know, or no eye mode (the protocol
     * `UNSPECIFIED`). An editor shows it as unknown and does not offer it as a choice. A write
     * sends the board's own number back, so the board keeps its mode until the user selects one.
     * The editors treat it as a sensor that is on, so they still show the eye fields.
     */
    UNKNOWN,
    ;

    companion object {
        /** The eye modes that an editor offers. [UNKNOWN] is not a mode that the user can select. */
        val selectable: List<MarkerEyeMode> = entries.filter { it != UNKNOWN }
    }
}
