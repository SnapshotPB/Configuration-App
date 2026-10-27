package com.snapshotpb.configuration.model

/**
 * Version of the device payload that the phone and the Wear app send on the Wear Data Layer.
 *
 * Set it higher than every version that a build shipped when you add a field to [syncedConfigEquals]
 * and [mergeFromPeer]. A gap between two versions is safe. Never set it lower. A peer on
 * an older build does not know the new field. It decodes the field as its default and sends that
 * default back with its next edit. [applyPeerDevice] rejects a payload with a lower version, so
 * that default cannot replace the real value.
 *
 * A new enum value is also a new field for this rule. Version 2 added [MarkerFireMode.UNKNOWN] and
 * [MarkerEyeMode.UNKNOWN]: an older peer decodes them as the property defaults, and its next edit
 * would replace an unknown board mode with a known one.
 *
 * - Version 3 adds [MarkerProfile.sourceSlot] to each synced profile. The watch writes to the board
 *   too, so it needs the source slot of each profile, and a peer on an older version would send
 *   each source slot back as `null`.
 * - Version 4 adds [MarkerFireMode.CUSTOM_RAMPING]. A peer on an older version decodes it as the
 *   property default (`SEMI`), and its next edit would replace the custom ramping mode. The NXL
 *   2026 mode is not a new value for this rule: its stored name did not change (see
 *   [MarkerFireMode.RAMPING_NXL_2026]).
 * - Version 5 adds [Device.boltMode], [BoardState.boltMode], and
 *   [MarkerProfile.searSolenoidDelay]. A peer on an older version decodes them as their defaults
 *   (`null` and `60`) and would overwrite open-bolt or custom sear-delay values on its next edit.
 *
 * The check protects the store of the newer build only. A peer on an older build still accepts a
 * payload with a higher version, and it keeps the default in its own store. That peer can then
 * write the default to a board, and it sends the default with the new version after its own
 * update. No change to a new build can correct a build that is already released, so the two apps
 * must get the update before the user selects a value that the older build does not know.
 */
const val DEVICE_SYNC_SCHEMA_VERSION: Int = 5

/** Data map key of [DEVICE_SYNC_SCHEMA_VERSION]. A payload without the key has version 0. */
const val DEVICE_SYNC_SCHEMA_VERSION_KEY: String = "schema_version"

/**
 * True when every field that the phone and the Wear app sync is the same in [other].
 *
 * This is the one list of synced fields. The store uses it to decide when to stamp
 * [Device.lastModified], and the two sync managers use it to decide when to send a device. Add a
 * new synced field here and in [mergeFromPeer], and increase [DEVICE_SYNC_SCHEMA_VERSION].
 *
 * [Device.boardState] is included: it is the divergence baseline. When it changes after a board
 * write, the peer must get the new baseline, or it shows "unsaved" for values that the board holds.
 *
 * [Device.photoPath] is included only so that a new photo starts a push of the photo asset. The
 * payload never carries the path (each sender clears it), so a change to the form of the path
 * needs no increase of [DEVICE_SYNC_SCHEMA_VERSION].
 */
fun Device.syncedConfigEquals(other: Device): Boolean =
    name == other.name &&
        bootText == other.bootText &&
        language == other.language &&
        screenBrightness == other.screenBrightness &&
        screenOffDelay == other.screenOffDelay &&
        displayWindow == other.displayWindow &&
        boltMode == other.boltMode &&
        activeProfileId == other.activeProfileId &&
        lockoutEnabled == other.lockoutEnabled &&
        // Plain equality, so a change of MarkerProfile.sourceSlot alone goes to the peer as well.
        knownProfiles == other.knownProfiles &&
        photoPath == other.photoPath &&
        boardState == other.boardState

/**
 * Returns this device with the synced fields of [incoming], a newer copy that a peer sent.
 *
 * - The id and [Device.photoPath] stay local. A photo path is a file on this device only.
 * - A board setting that the peer does not have (`null`) does not clear the local value.
 * - A peer without a [Device.boardState] does not clear the local baseline.
 * - [Device.lastSeen] keeps the later date.
 * - [Device.lastModified] comes from [incoming]. The caller sets the value that the payload sent.
 */
fun Device.mergeFromPeer(incoming: Device): Device =
    incoming.copy(
        id = id,
        photoPath = photoPath,
        lastSeen = maxOf(lastSeen, incoming.lastSeen),
        language = incoming.language ?: language,
        screenBrightness = incoming.screenBrightness ?: screenBrightness,
        screenOffDelay = incoming.screenOffDelay ?: screenOffDelay,
        displayWindow = incoming.displayWindow ?: displayWindow,
        boltMode = incoming.boltMode ?: boltMode,
        boardState = incoming.boardState ?: boardState,
        advertisementData = incoming.advertisementData ?: advertisementData,
    )

/** What [applyPeerDevice] did with a device that a peer sent. */
enum class PeerSyncOutcome {
    /** The device was not in the list, and it was added. */
    ADDED,

    /** The device was in the list, and the newer peer copy was merged into it. */
    UPDATED,

    /** The peer copy is not newer than the local copy, so nothing changed. */
    IGNORED_NOT_NEWER,

    /** The device was deleted on this side after the peer copy was made, so nothing changed. */
    IGNORED_DELETED,

    /** The peer sent an older payload schema, so nothing changed. See [DEVICE_SYNC_SCHEMA_VERSION]. */
    IGNORED_OLD_SCHEMA,
}

/** The result of [applyPeerDevice]: the new device list and the decision. */
data class PeerSyncResult(
    val devices: List<Device>,
    val outcome: PeerSyncOutcome,
)

/**
 * Applies a device that a peer sent on the Wear Data Layer to [current].
 *
 * This function is pure, so it can run as the transform of `DeviceStore.update {}`. The check and
 * the merge then read the same list, and a board read or a second event that lands at the same
 * time cannot be lost or add the same id two times.
 *
 * @param incoming the decoded device from the payload.
 * @param incomingLastModified the `last_modified` value of the payload.
 * @param peerSchemaVersion the [DEVICE_SYNC_SCHEMA_VERSION] of the payload (0 when absent).
 * @param localDeleteMarkerAt the time of a delete marker for this id on this side, or `null`. A
 *   device that is not in [current] and has a marker at or after [incomingLastModified] was
 *   deleted after the peer made its copy, so it does not come back.
 * @param localPhotoPath a photo path that this side saved from the payload, or `null` to keep the
 *   local path.
 */
fun applyPeerDevice(
    current: List<Device>,
    incoming: Device,
    incomingLastModified: Long,
    peerSchemaVersion: Int,
    localDeleteMarkerAt: Long? = null,
    localPhotoPath: String? = null,
): PeerSyncResult {
    if (peerSchemaVersion < DEVICE_SYNC_SCHEMA_VERSION) {
        return PeerSyncResult(current, PeerSyncOutcome.IGNORED_OLD_SCHEMA)
    }
    val existing = current.firstOrNull { it.id == incoming.id }
    if (existing == null) {
        if (localDeleteMarkerAt != null && localDeleteMarkerAt >= incomingLastModified) {
            return PeerSyncResult(current, PeerSyncOutcome.IGNORED_DELETED)
        }
        val added = incoming.copy(
            photoPath = localPhotoPath.orEmpty(),
            lastModified = incomingLastModified,
        )
        return PeerSyncResult(current + added, PeerSyncOutcome.ADDED)
    }
    if (incomingLastModified <= existing.lastModified) {
        return PeerSyncResult(current, PeerSyncOutcome.IGNORED_NOT_NEWER)
    }
    val merged = existing.mergeFromPeer(incoming).copy(
        photoPath = localPhotoPath ?: existing.photoPath,
        lastModified = incomingLastModified,
    )
    return PeerSyncResult(
        devices = current.map { if (it.id == existing.id) merged else it },
        outcome = PeerSyncOutcome.UPDATED,
    )
}
