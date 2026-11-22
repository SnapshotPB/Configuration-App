package com.snapshotpb.configuration.model

/**
 * The state of the breach eye sensor that the board reports.
 *
 * This enum mirrors the protocol `EyeSensorState`. It is not serializable and stays in memory
 * only.
 */
enum class EyeSensorState {
    /** The emitter is on. The board can report a sensor level. */
    ACTIVE,

    /** The active profile of the board has the eye mode `OFF`. */
    EYE_OFF,

    /** The eyes override of the board is off. */
    OVERRIDE_OFF,

    /** The eye channel latched a fault. */
    FAULT,

    /**
     * The protocol `UNSPECIFIED`, or a value that this app does not know. A client shows no level
     * and no hint for this state.
     */
    UNKNOWN,
}

/**
 * A live sensor sample of the breach eye from a connected board.
 *
 * This model is not serializable and stays in memory only. The app does not store it in
 * `devices.json` and does not send it in the Wear sync.
 *
 * @property levelRaw The last raw ADC sample. The board sends it only in the state
 *   [EyeSensorState.ACTIVE], and can omit it in [EyeSensorState.ACTIVE] before the first sample.
 * @property fullScaleRaw The full scale of the ADC in raw counts.
 * @property state The reported state of the breach eye sensor.
 */
data class EyeReading(
    val levelRaw: Int?,
    val fullScaleRaw: Int,
    val state: EyeSensorState,
) {
    /**
     * The sensor level in percent of [fullScaleRaw] (`levelRaw * 100.0 / fullScaleRaw`).
     *
     * The value is `null` when [levelRaw] is `null`, when [state] is not [EyeSensorState.ACTIVE],
     * or when [fullScaleRaw] is 0 or less.
     */
    val levelPercent: Double?
        get() = if (state == EyeSensorState.ACTIVE && levelRaw != null && fullScaleRaw > 0) {
            levelRaw * 100.0 / fullScaleRaw
        } else {
            null
        }
}
