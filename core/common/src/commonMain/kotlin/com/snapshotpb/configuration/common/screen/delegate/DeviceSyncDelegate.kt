package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.movedToBoardId
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.model.boardValuesMatch
import com.snapshotpb.configuration.model.equalsIgnoringSourceSlots
import com.snapshotpb.configuration.model.withPlaceholderModesAsUnknown
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.storedDevicePhotoName
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock.System.now
import kotlin.uuid.Uuid

/**
 * Observes OTS device reads and reconciles them against [DeviceStore].
 *
 * - First time a device is read → exposed as [PendingSync.NewDevice]; the UI confirms via
 *   [confirmNewDevice] (with a user-chosen name / photo) to add it to the store.
 * - Reconnect with matching device-sourced fields → silently bumps `lastSeen`.
 * - Reconnect after a board-side change, with no local edits → silently takes the board values,
 *   with the same result as [ConflictChoice.TakeDevice]. The app has nothing to lose.
 * - Reconnect after a board-side change, with local edits → exposed as [PendingSync.Conflict];
 *   the UI resolves via [resolveConflict] (all-or-nothing).
 */
interface DeviceSyncDelegate {
    val pendingSyncs: StateFlow<Map<Uuid, PendingSync>>
    suspend fun confirmNewDevice(deviceId: Uuid, name: String, photo: Photo?)
    suspend fun dismissNewDevice(deviceId: Uuid)
    suspend fun resolveConflict(deviceId: Uuid, choice: ConflictChoice)
}

sealed interface PendingSync {
    val deviceId: Uuid

    data class NewDevice(val device: Device) : PendingSync {
        override val deviceId: Uuid get() = device.id
    }

    data class Conflict(val local: Device, val remote: Device) : PendingSync {
        override val deviceId: Uuid get() = local.id
    }
}

enum class ConflictChoice { TakeDevice, TakeLocal }

class DeviceSyncDelegateImpl(
    private val deviceStore: DeviceStore,
    private val deviceReadResultBus: DeviceReadResultBus,
    private val bluetoothRequestManager: BluetoothRequestManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The stamp in the store name of a new photo. Tests supply their own values. */
    private val photoStamp: () -> Long = { now().toEpochMilliseconds() },
) : DeviceSyncDelegate {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val _pendingSyncs: MutableStateFlow<Map<Uuid, PendingSync>> =
        MutableStateFlow(emptyMap())
    override val pendingSyncs: StateFlow<Map<Uuid, PendingSync>> = _pendingSyncs

    init {
        scope.launch {
            deviceReadResultBus.results.collect { result ->
                moveDeviceStoredUnderScanKey(result)
                onDeviceRead(result.device)
            }
        }
    }

    /**
     * Moves a device that an older build stored under the scan key of [result] to the board id.
     *
     * Older builds used an id made from the scan key of the peripheral (#127), which is different
     * on each phone. The board `device_id` is the id now. The first read of the board finds the old
     * device through the scan key and moves it, with every field, in one store transform. The Wear
     * sync then sees the old id leave the store and writes its delete marker, so the watch does not
     * keep a stale copy. The photo goes to a file named for the new id, so a later delete of the
     * device also deletes its photo.
     *
     * The store can already hold the board id, for example when the phone moved the device first
     * and the Wear sync sent it to the watch. That copy is newer, so it stays, and the old device
     * goes. It takes the photo of the old device only when it has none.
     *
     * Nothing moves when no device has the old id. A board whose scan key changed since the old
     * build (an iOS reinstall) then shows as new.
     */
    private suspend fun moveDeviceStoredUnderScanKey(result: DeviceReadResult) {
        val newId = result.device.id
        val oldId = result.scanKey?.toDeviceUuid() ?: return
        if (oldId == newId) return
        deviceStore.awaitLoaded()
        val known = deviceStore.knownDevices.value
        val old = known.firstOrNull { it.id == oldId } ?: return
        val stored = known.firstOrNull { it.id == newId }
        log.d { "Moving device $oldId to its board id $newId (board id already stored: ${stored != null})" }

        val oldPhotoName = storedDevicePhotoName(oldId.toString(), old.photoPath)
        val newPhotoName = versionedDevicePhotoStoreName(newId.toString(), photoStamp())
        val newPhotoPath = oldPhotoName
            ?.takeIf { stored == null || stored.photoPath.isEmpty() }
            // The photo path is a store name (#175), not a URI, so copy the stored file itself.
            ?.let { oldName -> deviceStore.copyStoredPhoto(oldName, newPhotoName) }
        var photoMoved = false
        var oldPhotoUnused = false
        deviceStore.update { current ->
            photoMoved = false
            oldPhotoUnused = false
            val oldNow = current.firstOrNull { it.id == oldId } ?: return@update current
            val boardNow = current.firstOrNull { it.id == newId }
            val target = boardNow ?: oldNow.movedToBoardId(newId)
            photoMoved = newPhotoPath != null && oldNow.photoPath == old.photoPath && target.photoPath.let {
                it.isEmpty() || it == old.photoPath
            }
            // The old file stays in use only when the old device moved and kept its own path.
            oldPhotoUnused = photoMoved || boardNow != null
            val merged = if (photoMoved && newPhotoPath != null) target.copy(photoPath = newPhotoPath) else target
            current.mapNotNull { device ->
                when (device.id) {
                    oldId -> merged.takeIf { boardNow == null }
                    newId -> merged
                    else -> device
                }
            }
        }
        _pendingSyncs.update { it - oldId }
        // Delete each photo file that no device uses.
        if (oldPhotoUnused) oldPhotoName?.let(deviceStore::deleteStoredFile)
        if (!photoMoved && newPhotoPath != null) deviceStore.deleteStoredFile(newPhotoName)
    }

    private fun onDeviceRead(remote: Device) {
        val stored = deviceStore.knownDevices.value.firstOrNull { it.id == remote.id }
        // An older build stored MECHANICAL or OFF for a mode that the board reports as UNKNOWN now.
        // Move those placeholders to UNKNOWN first, so the read does not show a conflict for them.
        val local = stored?.withPlaceholderModesAsUnknown(remote)
        if (stored != null && local != stored) {
            log.d { "OTS read: board ${remote.id} reports an unknown mode, replacing the stored placeholder" }
            deviceStore.update { current ->
                current.map { device ->
                    if (device.id == remote.id) device.withPlaceholderModesAsUnknown(remote) else device
                }
            }
        }
        val localBoardState = local?.boardState
        when {
            local == null -> {
                log.d { "OTS read: new device ${remote.id}" }
                _pendingSyncs.update { it + (remote.id to PendingSync.NewDevice(remote)) }
            }
            localBoardState == null -> {
                if (local.deviceFieldsEqual(remote)) {
                    log.d { "OTS read: known device ${remote.id} matches local, initializing boardState" }
                    deviceStore.update { current ->
                        current.map { device ->
                            if (device.id == remote.id) {
                                device.copy(
                                    lastSeen = remote.lastSeen,
                                    // Equal but for the source slots, which the read knows.
                                    knownProfiles = remote.knownProfiles,
                                    // Read-only board facts always track the latest read.
                                    boardModel = remote.boardModel,
                                    supportedProfileCount = remote.supportedProfileCount,
                                    lockoutEnabled = remote.lockoutEnabled,
                                    // The firmware version of the latest advertisement (#129).
                                    advertisementData = remote.advertisementData ?: device.advertisementData,
                                    // Equal, or one side had none (see boardValuesMatch).
                                    language = remote.language,
                                    screenBrightness = remote.screenBrightness,
                                    screenOffDelay = remote.screenOffDelay,
                                    displayWindow = remote.displayWindow,
                                    boltMode = remote.boltMode,
                                    boardState = BoardState(
                                        bootText = remote.bootText,
                                        activeProfileId = remote.activeProfileId,
                                        lockoutEnabled = remote.lockoutEnabled,
                                        knownProfiles = remote.knownProfiles,
                                        language = remote.language,
                                        screenBrightness = remote.screenBrightness,
                                        screenOffDelay = remote.screenOffDelay,
                                        displayWindow = remote.displayWindow,
                                        boltMode = remote.boltMode,
                                    )
                                )
                            } else device
                        }
                    }
                    _pendingSyncs.update { it - remote.id }
                } else {
                    log.d { "OTS read: known device ${remote.id} diverges from local, awaiting resolution" }
                    _pendingSyncs.update { it + (remote.id to PendingSync.Conflict(local, remote)) }
                }
            }
            remote.matchesBoardState(localBoardState) -> {
                log.d { "OTS read: board matches cached boardState, bumping lastSeen and preserving pending changes" }
                // A board that reports nothing for a setting cannot be made to agree with a write,
                // so the stored value goes too. Say so: the user loses a choice without a prompt,
                // and the log is the only record of why the control then disappeared.
                if (remote.language == null && local.language != null) {
                    log.d { "OTS read: board ${remote.id} reports no language, clearing the stored one" }
                }
                if (remote.screenBrightness == null && local.screenBrightness != null) {
                    log.d { "OTS read: board ${remote.id} reports no screen brightness, clearing the stored one" }
                }
                if (remote.screenOffDelay == null && local.screenOffDelay != null) {
                    log.d { "OTS read: board ${remote.id} reports no screen-off delay, clearing the stored one" }
                }
                if (remote.displayWindow == null && local.displayWindow != null) {
                    log.d { "OTS read: board ${remote.id} reports no display window, clearing the stored one" }
                }
                if (remote.boltMode == null && local.boltMode != null) {
                    log.d { "OTS read: board ${remote.id} reports no bolt mode, clearing the stored one" }
                }
                deviceStore.update { current ->
                    current.map { device ->
                        if (device.id == remote.id) {
                            device.copy(
                                lastSeen = remote.lastSeen,
                                // Profiles with no local edit take the source slots of this read.
                                // A copy stored before the app kept source slots gets them here.
                                knownProfiles = if (
                                    device.knownProfiles.equalsIgnoringSourceSlots(remote.knownProfiles)
                                ) {
                                    remote.knownProfiles
                                } else {
                                    device.knownProfiles
                                },
                                // Read-only board facts always track the latest read — including
                                // lockoutEnabled, which is no longer part of divergence, so a
                                // board-side flip must be adopted here or the indicator goes stale.
                                boardModel = remote.boardModel,
                                supportedProfileCount = remote.supportedProfileCount,
                                lockoutEnabled = remote.lockoutEnabled,
                                // The firmware version of the latest advertisement (#129).
                                advertisementData = remote.advertisementData ?: device.advertisementData,
                                // A baseline with no value takes the board's, and a pending local
                                // choice is kept. A board that reports none clears both, because
                                // the app has no value that it can show or write.
                                language = remote.language?.let { device.language ?: it },
                                screenBrightness = remote.screenBrightness
                                    ?.let { device.screenBrightness ?: it },
                                screenOffDelay = remote.screenOffDelay
                                    ?.let { device.screenOffDelay ?: it },
                                displayWindow = remote.displayWindow
                                    ?.let { device.displayWindow ?: it },
                                boltMode = remote.boltMode
                                    ?.let { device.boltMode ?: it },
                                boardState = device.boardState?.let { base ->
                                    base.copy(
                                        // The same settings as before, with the source slots of this
                                        // read. Check again: the baseline can change after the check above.
                                        knownProfiles = if (base.knownProfiles.equalsIgnoringSourceSlots(remote.knownProfiles)) {
                                            remote.knownProfiles
                                        } else {
                                            base.knownProfiles
                                        },
                                        lockoutEnabled = remote.lockoutEnabled,
                                        language = remote.language,
                                        screenBrightness = remote.screenBrightness,
                                        screenOffDelay = remote.screenOffDelay,
                                        displayWindow = remote.displayWindow,
                                        boltMode = remote.boltMode,
                                    )
                                },
                            )
                        } else device
                    }
                }
                _pendingSyncs.update { it - remote.id }
            }
            else -> {
                // No local edits means that the app has nothing to lose. A change in the board
                // menu then needs no prompt, and "Keep local" cannot write an old value back.
                val tookBoard = !local.hasDiverged() && takeBoardIfNoLocalEdits(remote)
                if (tookBoard) {
                    log.d { "OTS read: board state changed externally, no local edits, taking the board values" }
                    _pendingSyncs.update { it - remote.id }
                } else {
                    log.d { "OTS read: board state changed externally over local edits, showing conflict" }
                    _pendingSyncs.update { it + (remote.id to PendingSync.Conflict(local, remote)) }
                }
            }
        }
    }

    override suspend fun confirmNewDevice(deviceId: Uuid, name: String, photo: Photo?) {
        // Take the entry before the slow photo copy, so a second tap finds nothing to confirm.
        val pending = takePending<PendingSync.NewDevice>(deviceId) ?: run {
            log.d { "confirmNewDevice($deviceId): no pending NewDevice entry" }
            return
        }
        val photoPath = photo?.let {
            // A stamped name, as in DeviceViewModelDelegate: Coil and the Wear sync then see a new
            // file for each new photo, and not a cached copy of an old one.
            deviceStore.storePhotoFromUri(versionedDevicePhotoStoreName(deviceId.toString(), photoStamp()), it.uri)
        } ?: ""
        val toStore = pending.device.copy(
            name = name,
            photoPath = photoPath,
            boardState = BoardState(
                bootText = pending.device.bootText,
                activeProfileId = pending.device.activeProfileId,
                lockoutEnabled = pending.device.lockoutEnabled,
                knownProfiles = pending.device.knownProfiles,
                language = pending.device.language,
                screenBrightness = pending.device.screenBrightness,
                screenOffDelay = pending.device.screenOffDelay,
                displayWindow = pending.device.displayWindow,
                boltMode = pending.device.boltMode,
            )
        )
        // Replace rather than append when the id is already present: mergeAgainst() does not
        // de-duplicate, so a double-confirm would otherwise leave two entries sharing one id.
        deviceStore.update { current ->
            if (current.any { it.id == toStore.id }) {
                current.map { if (it.id == toStore.id) toStore else it }
            } else {
                current + toStore
            }
        }
    }

    override suspend fun dismissNewDevice(deviceId: Uuid) {
        _pendingSyncs.update { it - deviceId }
    }

    override suspend fun resolveConflict(deviceId: Uuid, choice: ConflictChoice) {
        // Take the entry first and atomically. A double tap on a choice then resolves once and
        // sends one board write: the second call finds no entry.
        val pending = takePending<PendingSync.Conflict>(deviceId) ?: run {
            log.d { "resolveConflict($deviceId): no pending Conflict entry" }
            return
        }
        // Each result is built in the store transform from the device as it is stored NOW. A
        // rename, a photo change or an edit that landed while the dialog was open then stays.
        // The copy in the pending entry is only the snapshot from when the conflict was raised.
        val remote = pending.remote
        when (choice) {
            ConflictChoice.TakeDevice -> {
                deviceStore.update { current ->
                    current.map { device ->
                        if (device.id == deviceId) device.takeDevice(remote) else device
                    }
                }
            }
            ConflictChoice.TakeLocal -> {
                var writeArg: Device? = null
                deviceStore.update { current ->
                    current.map { device ->
                        if (device.id == deviceId) {
                            val (stored, toWrite) = device.keepLocal(remote)
                            writeArg = toWrite
                            stored
                        } else device
                    }
                }
                val toWrite = writeArg ?: run {
                    log.d { "resolveConflict($deviceId): the device is gone, no board write" }
                    return
                }
                if (remote.lockoutEnabled) {
                    // A board in lockout does not accept a change. The local values stay as
                    // unsaved edits, and the profiles screen tells the user why Save is disabled.
                    log.d { "resolveConflict($deviceId): the board reports lockout, no board write" }
                    return
                }
                bluetoothRequestManager.send(BluetoothRequest.WriteDevice(deviceId.toString(), toWrite))
            }
        }
    }

    /**
     * Stores the board copy of [remote], with the same result as [ConflictChoice.TakeDevice], when
     * the stored device has no local edits. Returns `false` and changes nothing when the stored
     * device has local edits. Returns `true` and changes nothing when the device is gone from the
     * store, because a deleted device needs no conflict.
     *
     * The transform examines the device as it is stored NOW. An edit that landed after the caller
     * read its snapshot then raises the conflict and is not lost.
     */
    private fun takeBoardIfNoLocalEdits(remote: Device): Boolean {
        var hasLocalEdits = false
        deviceStore.update { current ->
            // The transform can run again under contention, so each run sets the flag again.
            hasLocalEdits = false
            current.map { device ->
                when {
                    device.id != remote.id -> device
                    // With no baseline, hasDiverged() is false but the app cannot know about local
                    // edits, so the device keeps the prompt, as in the branch with no baseline.
                    device.boardState == null || device.hasDiverged() -> {
                        hasLocalEdits = true
                        device
                    }
                    else -> device.takeDevice(remote)
                }
            }
        }
        return !hasLocalEdits
    }

    /**
     * The board copy with the local-only fields of this device: the name, the photo and the
     * scan data. The baseline records the board copy, so no field shows as unsaved.
     */
    private fun Device.takeDevice(remote: Device): Device = remote.copy(
        name = name,
        photoPath = photoPath,
        lastModified = lastModified,
        // The latest advertisement gives the current firmware version (#129).
        advertisementData = remote.advertisementData ?: advertisementData,
        boardState = remote.boardBaseline(),
    )

    /**
     * Keeps the editable fields of this device and returns the device to store and the device to
     * write to the board.
     *
     * The device is clamped to the firmware ranges ONCE, and the clamped copy is used for both the
     * editable device and the write arg. If either used the pre-clamp value, the firmware would
     * store the clamped value and the badge would persist after this "keep my values" resolution.
     * onWriteResult later applies the identical clamp again, so this is idempotent.
     *
     * A local device with no value for a board setting takes the board's, so the write sends the
     * board's current value again and the two agree.
     */
    private fun Device.keepLocal(remote: Device): Pair<Device, Device> {
        // The source slots of the local profiles point at the board as the baseline recorded it.
        // When the board profiles changed since then, those slots are not the slots of this read,
        // which the write pairs against. Clear them, so the write pairs by position and content.
        val boardProfilesKept = boardState?.knownProfiles?.equalsIgnoringSourceSlots(remote.knownProfiles) == true
        val localProfiles = if (boardProfilesKept) {
            knownProfiles
        } else {
            knownProfiles.map { it.copy(sourceSlot = null) }
        }
        val clampedLocal = copy(knownProfiles = localProfiles).clampedToFirmwareRanges().let { local ->
            local.copy(
                language = local.language ?: remote.language,
                screenBrightness = local.screenBrightness ?: remote.screenBrightness,
                screenOffDelay = local.screenOffDelay ?: remote.screenOffDelay,
                displayWindow = local.displayWindow ?: remote.displayWindow,
                boltMode = local.boltMode ?: remote.boltMode,
            )
        }
        val mergedLocal = clampedLocal.copy(
            lastSeen = remote.lastSeen,
            // Read-only board facts always track the latest read, even when the user keeps
            // their local settings — lockoutEnabled included (adopt the board's value).
            boardModel = remote.boardModel,
            supportedProfileCount = remote.supportedProfileCount,
            lockoutEnabled = remote.lockoutEnabled,
            advertisementData = remote.advertisementData ?: clampedLocal.advertisementData,
            // The baseline records what the board holds NOW, the same rule as a save from
            // the profiles screen: the write result settles it when the board accepts the
            // write (DeviceViewModelDelegate.onWriteResult). An optimistic baseline hid a
            // failed write: the store then claimed that the board held the local values, so
            // no banner, no error and no "Save to board" button showed until the next
            // connection raised the same conflict again.
            boardState = remote.boardBaseline(),
        )
        return mergedLocal to clampedLocal
    }

    /** The baseline that records the board-sourced fields of this read. */
    private fun Device.boardBaseline(): BoardState = BoardState(
        bootText = bootText,
        activeProfileId = activeProfileId,
        lockoutEnabled = lockoutEnabled,
        knownProfiles = knownProfiles,
        language = language,
        screenBrightness = screenBrightness,
        screenOffDelay = screenOffDelay,
        displayWindow = displayWindow,
        boltMode = boltMode,
    )

    /**
     * Removes the pending entry of type [T] for [deviceId] and returns it, in one atomic step.
     * Returns `null` and changes nothing when there is no entry of that type.
     */
    private inline fun <reified T : PendingSync> takePending(deviceId: Uuid): T? {
        while (true) {
            val current = _pendingSyncs.value
            val entry = current[deviceId] as? T ?: return null
            if (_pendingSyncs.compareAndSet(current, current - deviceId)) return entry
        }
    }

    // lockoutEnabled is intentionally excluded: it is a read-only board fact, not a divergence
    // field, so a board-side lockout flip is reconciled (indicator adopts it) rather than raising
    // an unresolvable conflict.
    private fun Device.matchesBoardState(bs: BoardState): Boolean =
        bootText == bs.bootText &&
            activeProfileId == bs.activeProfileId &&
            boardValuesMatch(bs.language, language) &&
            boardValuesMatch(bs.screenBrightness, screenBrightness) &&
            boardValuesMatch(bs.screenOffDelay, screenOffDelay) &&
            boardValuesMatch(bs.displayWindow, displayWindow) &&
            boardValuesMatch(bs.boltMode, boltMode) &&
            // The source slot is not a setting, so it raises no conflict.
            knownProfiles.equalsIgnoringSourceSlots(bs.knownProfiles)

    companion object {
        private val log = Arbor.tag("DeviceSyncDelegate")
    }
}

/**
 * Equality across the writable device-sourced fields only. Local-only fields (`name`,
 * `photoPath`, `lastSeen`) are excluded so that a user-rename or a reconnect-on-the-same-day does
 * not spuriously surface as a conflict; `lockoutEnabled` is excluded too because it is a read-only
 * board fact that is adopted, not diffed.
 */
private fun Device.deviceFieldsEqual(other: Device): Boolean =
    id == other.id &&
        bootText == other.bootText &&
        activeProfileId == other.activeProfileId &&
        boardValuesMatch(language, other.language) &&
        boardValuesMatch(screenBrightness, other.screenBrightness) &&
        boardValuesMatch(screenOffDelay, other.screenOffDelay) &&
        boardValuesMatch(displayWindow, other.displayWindow) &&
        boardValuesMatch(boltMode, other.boltMode) &&
        knownProfiles.equalsIgnoringSourceSlots(other.knownProfiles)
