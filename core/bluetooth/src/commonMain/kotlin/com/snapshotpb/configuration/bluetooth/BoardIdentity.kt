package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.model.Device
import kotlin.uuid.Uuid

/**
 * The board id of [id] when [id] is a scan key with a known board id, else [id] itself.
 *
 * The UI opens a board that it found in a scan with the scan key when the board id is not known yet.
 * A read, or on Android the 0xFFE1 advertisement, puts the board id in [boardIds] (see
 * [BluetoothStateManager.boardIds]). That id is the [Device.id] that the store holds.
 */
fun resolveBoardId(boardIds: Map<String, String>, id: String): String = boardIds[id] ?: id

/**
 * The scan key of the board [boardId] in [boardIds], or `null` when this session does not know the
 * scan key of that board.
 */
fun scanKeyOfBoard(boardIds: Map<String, String>, boardId: String): String? =
    boardIds.entries.firstOrNull { (_, id) -> id == boardId }?.key

/**
 * This device moved to the board id [newId].
 *
 * Older builds stored a board under an id made from its scan key (see [toDeviceUuid]). The first
 * read of the board with the board `device_id` moves that device to [newId] and keeps every field.
 * The profile ids come from the device id and the slot (see [getProfileUuid]), so each id that the
 * old device id gave moves to the id that [newId] gives for the same slot. An id that the old
 * device id did not give, for example a profile that the user added and did not save, stays as it
 * is.
 */
fun Device.movedToBoardId(newId: Uuid): Device {
    val oldId = id
    fun Uuid.moved(): Uuid = slotOf(oldId)?.let { slot -> getProfileUuid(newId, slot) } ?: this
    return copy(
        id = newId,
        knownProfiles = knownProfiles.map { it.copy(id = it.id.moved()) },
        activeProfileId = activeProfileId?.moved(),
        boardState = boardState?.let { state ->
            state.copy(
                knownProfiles = state.knownProfiles.map { it.copy(id = it.id.moved()) },
                activeProfileId = state.activeProfileId?.moved(),
            )
        },
    )
}

/**
 * The slot that [deviceId] gives this profile id through [getProfileUuid], or `null` when the id
 * does not come from [deviceId]. [getProfileUuid] changes only the last byte, so the first 15
 * bytes must agree and the last byte gives the slot.
 */
private fun Uuid.slotOf(deviceId: Uuid): Int? {
    val profileBytes = toByteArray()
    val deviceBytes = deviceId.toByteArray()
    for (index in 0 until LAST_BYTE) {
        if (profileBytes[index] != deviceBytes[index]) return null
    }
    return (profileBytes[LAST_BYTE].toInt() xor deviceBytes[LAST_BYTE].toInt()) and 0xFF
}

private const val LAST_BYTE = 15
