package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.model.Device

/**
 * Gives every profile the id that a board read derives for its slot.
 *
 * The board has no profile id of its own. A read derives each id from the device id and the slot
 * index ([getProfileUuid]), and a write puts `knownProfiles[i]` into slot `i`. So the id of a
 * profile is a function of its position, and the app must keep that rule when it adds or deletes a
 * profile. A random id, or an id left over from a higher slot after a delete, survives the write
 * and comes back from the next read with the slot id. The two lists then differ only in `id`, and
 * the app shows a conflict after every save that "Take local" can never clear.
 *
 * The active profile follows its profile to the new id. An active id that matches no profile is
 * kept as it is, because the write already sends "no active profile" for it.
 */
internal fun Device.withSlotProfileIds(): Device {
    val remapped = knownProfiles.mapIndexed { index, profile ->
        profile.copy(id = getProfileUuid(id, index))
    }
    val activeIndex = knownProfiles.indexOfFirst { it.id == activeProfileId }
    return copy(
        knownProfiles = remapped,
        activeProfileId = if (activeIndex >= 0) remapped[activeIndex].id else activeProfileId,
    )
}
