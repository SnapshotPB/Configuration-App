package com.snapshotpb.configuration.model

/**
 * This device with each stored placeholder mode moved to `UNKNOWN` where [remote], a new board
 * read, reports `UNKNOWN`.
 *
 * Builds before [MarkerFireMode.UNKNOWN] and [MarkerEyeMode.UNKNOWN] read an unknown mode, or the
 * protocol `UNSPECIFIED`, as [MarkerFireMode.MECHANICAL] or [MarkerEyeMode.OFF]. Such a build
 * stored that placeholder in the profile and in the baseline. Without this step, the first read
 * after the update shows a conflict, and "keep my values" writes the placeholder over the mode of
 * the board.
 *
 * - A baseline profile that holds the placeholder where the board profile with the same id holds
 *   `UNKNOWN` changes to `UNKNOWN`.
 * - The editable profile with the same id changes too, but only when it still holds the baseline
 *   value. A mode that the user selected stays.
 *
 * A device without a baseline does not change. Without a baseline, this function cannot know if
 * the user selected the mode, so such a device can still show a conflict for it. This function is
 * pure, so it can run as the transform of `DeviceStore.update {}`.
 */
fun Device.withPlaceholderModesAsUnknown(remote: Device): Device {
    val baseline = boardState ?: return this
    val boardById = remote.knownProfiles.associateBy { it.id }
    val baselineById = baseline.knownProfiles.associateBy { it.id }
    return copy(
        knownProfiles = knownProfiles.map { profile ->
            profile.withPlaceholderModesAsUnknown(baselineById[profile.id], boardById[profile.id])
        },
        boardState = baseline.copy(
            knownProfiles = baseline.knownProfiles.map { profile ->
                profile.withPlaceholderModesAsUnknown(profile, boardById[profile.id])
            },
        ),
    )
}

private fun MarkerProfile.withPlaceholderModesAsUnknown(
    base: MarkerProfile?,
    board: MarkerProfile?,
): MarkerProfile {
    if (base == null || board == null) return this
    return copy(
        fireMode = placeholderAsUnknown(
            value = fireMode,
            base = base.fireMode,
            board = board.fireMode,
            placeholder = MarkerFireMode.MECHANICAL,
            unknown = MarkerFireMode.UNKNOWN,
        ),
        eyeMode = placeholderAsUnknown(
            value = eyeMode,
            base = base.eyeMode,
            board = board.eyeMode,
            placeholder = MarkerEyeMode.OFF,
            unknown = MarkerEyeMode.UNKNOWN,
        ),
    )
}

/** [unknown] when the board reports [unknown], the baseline holds [placeholder] and [value] is the baseline value. */
private fun <T> placeholderAsUnknown(value: T, base: T, board: T, placeholder: T, unknown: T): T =
    if (board == unknown && base == placeholder && value == base) unknown else value
