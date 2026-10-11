package com.snapshotpb.configuration.model

/**
 * True when both lists hold the same profiles in the same order, with [MarkerProfile.sourceSlot]
 * not compared (see [MarkerProfile.equalsIgnoringSourceSlot]). Use it for each check that the user
 * sees: an "unsaved" badge or a conflict must not come from a value the user cannot see.
 */
fun List<MarkerProfile>.equalsIgnoringSourceSlots(other: List<MarkerProfile>): Boolean =
    size == other.size && zip(other).all { (mine, theirs) -> mine.equalsIgnoringSourceSlot(theirs) }

/**
 * Returns a copy in which each profile has the board slot of its list index as its
 * [MarkerProfile.sourceSlot]. A successful write puts `knownProfiles[i]` in slot `i`, so this is the
 * state of the device after the write.
 */
fun Device.withProfilesInWrittenSlots(): Device =
    copy(knownProfiles = knownProfiles.mapIndexed { index, profile -> profile.copy(sourceSlot = index) })

/**
 * Returns these local profiles with the source slots that they have after a write of [requested]
 * finished while the user edited the device.
 *
 * The board now holds `requested[j]` in slot `j` for each `j` below [writtenCount]. A local profile
 * is `requested[j]` when both have the same source slot, or when both have no source slot and the
 * same id (a profile that the user added before the write). A local profile that matches no written
 * profile gets no source slot.
 *
 * The id of a profile follows its list position, so the id match for a profile with no source
 * slot is a position match. A delete and an add during the write can then give the slot of one new
 * profile to a different new profile. The effect is small: the board holds the default
 * `board_auto_off` for each new profile.
 */
fun List<MarkerProfile>.withSourceSlotsAfterWrite(
    requested: List<MarkerProfile>,
    writtenCount: Int,
): List<MarkerProfile> = map { local ->
    val slot = requested.indexOfFirst { sent ->
        if (local.sourceSlot != null) {
            sent.sourceSlot == local.sourceSlot
        } else {
            sent.sourceSlot == null && sent.id == local.id
        }
    }
    local.copy(sourceSlot = slot.takeIf { it in 0 until writtenCount })
}
