package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BoardChangedBeforeWriteException
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.model.equalsIgnoringSourceSlots
import com.snapshotpb.configuration.model.limitBootText
import com.snapshotpb.configuration.model.limitDeviceName
import com.snapshotpb.configuration.model.withSourceSlotsAfterWrite
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.storedDevicePhotoName
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
// lastSeen is a local, user-facing date. It must use the same zone as the OTS read path
// (getCurrentLocalDate), or the same reconnect stamps two different dates west of UTC.
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock.System.now
import kotlin.uuid.Uuid

/** The result of one write that [DeviceViewModelDelegate.saveChangesToBoard] started. */
data class SaveResult(val deviceId: Uuid, val succeeded: Boolean)

interface DeviceViewModelDelegate {
    /** Device ids whose most recent "save to board" write failed and remain unsaved. */
    val writeErrors: StateFlow<Set<Uuid>>

    /**
     * Device ids with a "save to board" write that has no result yet. The UI disables Save for
     * these devices, and [saveChangesToBoard] ignores a second request, so a double tap sends
     * one write.
     */
    val writesInFlight: StateFlow<Set<Uuid>>

    /**
     * Emits one [SaveResult] when the result of a write that [saveChangesToBoard] started arrives.
     * A write with no result before the timeout emits a failure. The write of a conflict
     * resolution emits nothing. A write that the engine stopped because the board changed
     * ([BoardChangedBeforeWriteException]) emits nothing, because the conflict tells the user. A screen uses this flow for a short message. [writeErrors] keeps
     * the failure.
     */
    val saveResults: SharedFlow<SaveResult>

    fun updateDevice(
        deviceId: String,
        name: String,
        photo: Photo?
    )

    fun updateDeviceConfiguration(
        deviceId: String,
        name: String,
        bootText: String,
        activeProfileId: Uuid?,
        language: BoardLanguage?,
        screenBrightness: BoardScreenBrightness?,
        screenOffDelay: Int?,
        displayWindow: DisplayWindow?,
        boltMode: MarkerBoltMode? = null,
    )

    /**
     * Replaces the whole profile with the same id as [profile] in the device's `knownProfiles`.
     * The store keeps the profile after [MarkerProfile.clampedToFirmwareRanges], so the value the
     * user sees is the one the board will accept. The phone editor commits its draft here on Save.
     */
    fun updateProfile(deviceId: String, profile: MarkerProfile)

    /**
     * Sets one [field] of the profile [profileId] to [value] on top of the profile the store holds
     * at commit time. A watch stepper commits here, so a newer phone push that landed during a
     * hold keeps its other fields.
     */
    fun updateProfileField(deviceId: String, profileId: Uuid, field: MarkerProfileField, value: Int)

    /**
     * Applies [change] to the profile [profileId] that the store holds at commit time, inside one
     * atomic store update. A watch list (the fire mode, the eye mode) commits here, so a phone push
     * that lands before the commit keeps its other fields. [change] can run more than once, so it
     * must have no side effects. A result equal to the stored profile changes nothing. The store
     * keeps a changed result after [MarkerProfile.clampedToFirmwareRanges], as in the other
     * [updateProfile].
     */
    fun updateProfile(deviceId: String, profileId: Uuid, change: (MarkerProfile) -> MarkerProfile)

    /**
     * Applies [change] to the board settings of the device that the store holds at commit time,
     * inside one atomic store update. The board settings are the active profile, the language, the
     * screen brightness and the screen-off delay; the other fields of the result are ignored. A
     * `null` language, brightness or delay keeps the stored value, as in
     * [updateDeviceConfiguration]. A watch list commits here, so a phone push that lands before the
     * commit keeps its values. [change] can run more than once, so it must have no side effects. A
     * result equal to the stored settings changes nothing.
     */
    fun updateBoardSettings(deviceId: String, change: (Device) -> Device)

    /**
     * Restores the profile [profileId] from the cached board state and leaves the boot text, the
     * board settings and the other profiles alone. [discardPendingChanges] restores the whole
     * device.
     */
    fun discardProfileChanges(deviceId: String, profileId: Uuid)

    /**
     * Writes the stored device to the board. A device that reports lockout gets no write, because
     * a board in lockout does not accept a change until the user turns off its lockout switch.
     */
    suspend fun saveChangesToBoard(deviceId: String)
    fun discardPendingChanges(deviceId: String)
    suspend fun connect(deviceId: String)
    suspend fun disconnect(deviceId: String)
}

class DeviceViewModelDelegateImpl(
    private val deviceStore: DeviceStore,
    private val bluetoothRequestManager: BluetoothRequestManager,
    deviceWriteResultBus: DeviceWriteResultBus,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The stamp in the store name of a new photo. Tests supply their own values. */
    private val photoStamp: () -> Long = { now().toEpochMilliseconds() },
) : DeviceViewModelDelegate {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val _writeErrors = MutableStateFlow<Set<Uuid>>(emptySet())
    override val writeErrors: StateFlow<Set<Uuid>> = _writeErrors

    /** The write in flight for each device, keyed to a token, so a late watchdog cannot end a newer write. */
    private val inFlightWrites = MutableStateFlow<Map<Uuid, Long>>(emptyMap())
    private val writeTokens = MutableStateFlow(0L)
    override val writesInFlight: StateFlow<Set<Uuid>> = inFlightWrites
        .map { it.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // A collector that is busy must not stop the write-result observer, so the buffer drops the
    // oldest message. With no collector, a message goes nowhere: only a screen on view needs it.
    private val _saveResults = MutableSharedFlow<SaveResult>(
        extraBufferCapacity = SAVE_RESULT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val saveResults: SharedFlow<SaveResult> = _saveResults.asSharedFlow()

    init {
        scope.launch {
            deviceWriteResultBus.results.collect(::onWriteResult)
        }
    }

    override fun updateDevice(
        deviceId: String,
        name: String,
        photo: Photo?
    ) {
        val deviceUuid = Uuid.parse(deviceId)
        if (deviceStore.knownDevices.value.none { it.id == deviceUuid }) {
            log.e { "Attempted to save unknown device $deviceUuid" }
            return
        }

        val updatedName = name.limitDeviceName()
        val seenDate = now().toLocalDateTime(timeZone = TimeZone.currentSystemDefault()).date

        // Apply the name/last-seen edit immediately; photo copying is slow IO and must not block the
        // caller (main) thread, so it runs on the delegate scope and applies its path when ready.
        deviceStore.update { current ->
            current.map { device ->
                if (device.id == deviceUuid) {
                    device.copy(name = updatedName, lastSeen = seenDate)
                } else {
                    device
                }
            }
        }

        if (photo != null) {
            val sourceUri = photo.uri
            scope.launch {
                // Each photo gets a new file name, so the path changes with the content (see
                // versionedDevicePhotoStoreName).
                val storeName = versionedDevicePhotoStoreName(deviceId, photoStamp())
                val storedPhotoPath =
                    deviceStore.storePhotoFromUri(storeName, sourceUri) ?: return@launch
                // The transform can run more than once, so it sets these two values again on
                // each run.
                var applied = false
                var replacedPath: String? = null
                deviceStore.update { current ->
                    applied = false
                    replacedPath = null
                    current.map { device ->
                        if (device.id == deviceUuid) {
                            applied = true
                            replacedPath = device.photoPath
                            device.copy(photoPath = storedPhotoPath)
                        } else {
                            device
                        }
                    }
                }
                if (!applied) {
                    // The device was deleted during the copy, so nothing refers to the new file.
                    deviceStore.deleteStoredFile(storeName)
                    return@launch
                }
                // Delete the file that the new photo replaced, but only a photo file of this device.
                replacedPath
                    ?.let { storedDevicePhotoName(deviceId, it) }
                    ?.takeIf { it != storeName }
                    ?.let(deviceStore::deleteStoredFile)
            }
        }
    }

    override fun updateDeviceConfiguration(
        deviceId: String,
        name: String,
        bootText: String,
        activeProfileId: Uuid?,
        language: BoardLanguage?,
        screenBrightness: BoardScreenBrightness?,
        screenOffDelay: Int?,
        displayWindow: DisplayWindow?,
        boltMode: MarkerBoltMode?,
    ) {
        val deviceUuid = Uuid.parse(deviceId)
        val deviceToUpdate = deviceStore.knownDevices.value.firstOrNull { it.id == deviceUuid }
        if (deviceToUpdate == null) {
            log.e { "Attempted to save unknown device $deviceUuid" }
            return
        }

        // New edits supersede any prior failed write, so the stale error must not linger.
        _writeErrors.update { it - deviceUuid }

        val updatedName = name.limitDeviceName()
        val updatedBootText = bootText.limitBootText()
        val seenDate = now().toLocalDateTime(timeZone = TimeZone.currentSystemDefault()).date

        deviceStore.update { current ->
            current.map { device ->
                if (device.id == deviceUuid) {
                    device.copy(
                        name = updatedName,
                        bootText = updatedBootText,
                        activeProfileId = activeProfileId,
                        // null is "no choice" for each of the settings the board can refuse:
                        // it must not clear a value the board reported.
                        language = language ?: device.language,
                        screenBrightness = screenBrightness ?: device.screenBrightness,
                        screenOffDelay = screenOffDelay ?: device.screenOffDelay,
                        // Clamped here as well as at the write boundary, so the value the user sees
                        // after a save is the one the board will accept.
                        displayWindow = displayWindow?.clampedToPanel() ?: device.displayWindow,
                        boltMode = boltMode ?: device.boltMode,
                        lastSeen = seenDate,
                    )
                } else {
                    device
                }
            }
        }
    }

    override fun updateProfile(deviceId: String, profile: MarkerProfile) {
        updateProfiles(deviceId) { device ->
            // The editor holds a copy from the time the screen opened. The source slot is not a
            // setting, and a read or a write can change it after that time, so keep the stored slot.
            device.knownProfiles.map {
                if (it.id == profile.id) profile.clampedToFirmwareRanges().copy(sourceSlot = it.sourceSlot) else it
            }
        }
    }

    override fun updateProfileField(
        deviceId: String,
        profileId: Uuid,
        field: MarkerProfileField,
        value: Int,
    ) {
        // The transform reads the profile inside the store's atomic update, so the commit lands on
        // the profile the store holds now, not on the copy the stepper started from.
        updateProfiles(deviceId) { device ->
            device.knownProfiles.map {
                if (it.id == profileId) field.set(it, value).clampedToFirmwareRanges() else it
            }
        }
    }

    override fun updateProfile(
        deviceId: String,
        profileId: Uuid,
        change: (MarkerProfile) -> MarkerProfile,
    ) {
        updateDeviceIfChanged(deviceId) { device ->
            device.copy(
                knownProfiles = device.knownProfiles.map { profile ->
                    if (profile.id != profileId) return@map profile
                    // An unchanged profile stays as stored, so a choice of the current value is
                    // no edit, even for a stored value that the clamp would move.
                    val changed = change(profile)
                    if (changed == profile) profile else changed.clampedToFirmwareRanges()
                }
            )
        }
    }

    override fun updateBoardSettings(deviceId: String, change: (Device) -> Device) {
        val seenDate = now().toLocalDateTime(timeZone = TimeZone.currentSystemDefault()).date
        updateDeviceIfChanged(deviceId) { device ->
            val changed = change(device)
            val updated = device.copy(
                activeProfileId = changed.activeProfileId,
                // null is "no choice", as in updateDeviceConfiguration: it must not clear a value
                // the board reported.
                language = changed.language ?: device.language,
                screenBrightness = changed.screenBrightness ?: device.screenBrightness,
                screenOffDelay = changed.screenOffDelay ?: device.screenOffDelay,
                boltMode = changed.boltMode ?: device.boltMode,
            )
            if (updated == device) device else updated.copy(lastSeen = seenDate)
        }
    }

    /**
     * Replaces the device [deviceId] with the result of [transform] inside one atomic store update.
     * A stale write error is cleared only when the result differs from the stored device, because
     * a new edit supersedes a prior failed write, but a choice of the value the device already holds
     * is no edit. [transform] can run more than once, so it must read every input from the device
     * it receives.
     */
    private fun updateDeviceIfChanged(deviceId: String, transform: (Device) -> Device) {
        val deviceUuid = Uuid.parse(deviceId)
        // The transform can run more than once, so each run sets these two values again.
        var found = false
        var changed = false
        deviceStore.update { current ->
            found = false
            changed = false
            current.map { device ->
                if (device.id != deviceUuid) return@map device
                found = true
                val updated = transform(device)
                changed = updated != device
                if (changed) updated else device
            }
        }
        when {
            !found -> log.e { "Attempted to save unknown device $deviceUuid" }
            changed -> _writeErrors.update { it - deviceUuid }
        }
    }

    override fun discardProfileChanges(deviceId: String, profileId: Uuid) {
        // The board copy is read inside the atomic update as well. A write result that lands
        // between a snapshot and the commit moves the baseline, and a retry of the transform must
        // restore the new baseline, not the old one.
        updateProfiles(deviceId) { device ->
            val boardProfile = device.boardState?.knownProfiles?.firstOrNull { it.id == profileId }
                ?: return@updateProfiles device.knownProfiles
            // The settings come from the board, but the source slot must fit the local list. A
            // list with no source slots (a copy stored before the app kept them) must stay that
            // way, or the write stops the content alignment for the other profiles. A slot that
            // another local profile holds (after a delete) must not go to two profiles.
            val hasSourceSlots = device.knownProfiles.any { it.sourceSlot != null }
            val slot = boardProfile.sourceSlot?.takeIf { boardSlot ->
                hasSourceSlots && device.knownProfiles.none { it.id != profileId && it.sourceSlot == boardSlot }
            }
            device.knownProfiles.map { if (it.id == profileId) boardProfile.copy(sourceSlot = slot) else it }
        }
    }

    /**
     * Replaces the `knownProfiles` of the device [deviceId] with the result of [transform] and
     * clears a stale write error for it, because a new edit supersedes a prior failed write.
     * [transform] runs inside the store's atomic update and can run more than once, so it must
     * read every input from the device it receives.
     */
    private fun updateProfiles(deviceId: String, transform: (Device) -> List<MarkerProfile>) {
        val deviceUuid = Uuid.parse(deviceId)
        if (deviceStore.knownDevices.value.none { it.id == deviceUuid }) {
            log.e { "Attempted to save unknown device $deviceUuid" }
            return
        }

        _writeErrors.update { it - deviceUuid }

        deviceStore.update { current ->
            current.map { device ->
                if (device.id == deviceUuid) {
                    device.copy(knownProfiles = transform(device))
                } else {
                    device
                }
            }
        }
    }

    override suspend fun saveChangesToBoard(deviceId: String) {
        val deviceUuid = Uuid.parse(deviceId)
        val device = deviceStore.knownDevices.value.firstOrNull { it.id == deviceUuid } ?: return
        if (device.lockoutEnabled) {
            // The UI disables Save in lockout. This check stops a request that came before the
            // board reported the lockout.
            log.d { "$deviceUuid reports lockout; the board does not accept a write" }
            return
        }

        // Claim the write atomically first, so a second tap before the result sends nothing.
        val token = claimWrite(deviceUuid) ?: run {
            log.d { "A write to $deviceUuid is already in flight; ignoring the second request" }
            return
        }
        // The engine always reports a result, except when the write is cancelled. The watchdog
        // then releases the claim, so Save cannot stay disabled for the life of the process. A
        // write with no result did not reach the board as far as the app knows, so the watchdog
        // records a failure. A late result of this write still settles the store and the error.
        scope.launch {
            delay(WRITE_RESULT_TIMEOUT_MILLIS)
            if (releaseWrite(deviceUuid, token)) {
                log.e { "No result for the write to $deviceUuid; leaving local changes unsaved" }
                _writeErrors.update { it + deviceUuid }
                _saveResults.tryEmit(SaveResult(deviceUuid, succeeded = false))
            }
        }

        // Clear any prior failure for this device; the write-result observer re-flags on failure.
        _writeErrors.update { it - deviceUuid }

        // Request the BLE write. Divergence (cached boardState) is cleared only once the board
        // actually accepts the write — see onWriteResult — so the banner stays honest on failure.
        // The token travels with the request and comes back in the result, so only the result of
        // this write can release this claim.
        bluetoothRequestManager.send(BluetoothRequest.WriteDevice(deviceId, device, requestId = token))
    }

    /**
     * Records a write in flight for [deviceUuid]. Returns its token, or `null` when one is already
     * in flight. A token is always 1 or more, so the request id `0` of an untracked write (a
     * conflict resolution) never matches a claim.
     */
    private fun claimWrite(deviceUuid: Uuid): Long? {
        val token = writeTokens.updateAndGet { it + 1 }
        var claimed = false
        inFlightWrites.update { current ->
            claimed = deviceUuid !in current
            if (claimed) current + (deviceUuid to token) else current
        }
        return token.takeIf { claimed }
    }

    /**
     * Ends the write in flight for [deviceUuid], but only when it is the write with [token].
     * Returns true when this call ended the write.
     */
    private fun releaseWrite(deviceUuid: Uuid, token: Long): Boolean {
        // The transform can run more than once, so it sets the value again on each run.
        var released = false
        inFlightWrites.update { current ->
            released = current[deviceUuid] == token
            if (released) current - deviceUuid else current
        }
        return released
    }

    /**
     * Reconciles the local store with the outcome of a board write. [result.device] is the
     * *clamped* configuration the board actually stored (the engine clamps to the firmware ranges
     * before writing). On success both the editable device **and** the cached [BoardState] baseline
     * are set to that clamped configuration, so `editable == wire == baseline` and divergence
     * clears — including the case where the firmware bounded a value. Rewriting only the baseline
     * (the old behavior) left the editable device at its pre-clamp value and produced a permanent,
     * no-exit "unsaved" badge (finding 3b). On failure the local edits are left intact and the
     * device is flagged in [writeErrors]. A write that did not start because the board changed
     * ([BoardChangedBeforeWriteException]) is not flagged: the conflict dialog tells the user.
     *
     * `lockoutEnabled` is a read-only board fact and is deliberately not stamped here; the existing
     * baseline value (kept as the indicator source) is preserved.
     *
     * Observes every [BluetoothRequest.WriteDevice] — both [saveChangesToBoard] and
     * `DeviceSyncDelegate.resolveConflict(TakeLocal)`. Both leave the baseline at the board's
     * last-read values, so divergence stays visible until the board accepts the write here.
     */
    private fun onWriteResult(result: DeviceWriteResult) {
        val written = result.device
        val requested = result.requestedDevice
        val error = result.error
        // Only the result of the claimed write releases the claim. The result of a conflict write
        // or of an older, timed-out write must not enable Save while the current write runs.
        val savedByUser = releaseWrite(written.id, result.requestId)
        if (error == null) {
            deviceStore.update { current ->
                current.map { dev ->
                    if (dev.id == written.id) {
                        // The baseline always tracks what the board now holds (the clamped write).
                        val baselineLanguage = settledBoardValue(
                            written = written.language,
                            requested = requested?.language,
                            baseline = dev.boardState?.language,
                        )
                        val baselineBrightness = settledBoardValue(
                            written = written.screenBrightness,
                            requested = requested?.screenBrightness,
                            baseline = dev.boardState?.screenBrightness,
                        )
                        val baselineDelay = settledBoardValue(
                            written = written.screenOffDelay,
                            requested = requested?.screenOffDelay,
                            baseline = dev.boardState?.screenOffDelay,
                        )
                        val baselineDisplayWindow = settledBoardValue(
                            written = written.displayWindow,
                            requested = requested?.displayWindow,
                            baseline = dev.boardState?.displayWindow,
                        )
                        val baselineBoltMode = settledBoardValue(
                            written = written.boltMode,
                            requested = requested?.boltMode,
                            baseline = dev.boardState?.boltMode,
                        )
                        val baseline = dev.boardState?.copy(
                            bootText = written.bootText,
                            activeProfileId = written.activeProfileId,
                            knownProfiles = written.knownProfiles,
                            language = baselineLanguage,
                            screenBrightness = baselineBrightness,
                            screenOffDelay = baselineDelay,
                            displayWindow = baselineDisplayWindow,
                            boltMode = baselineBoltMode,
                        ) ?: BoardState(
                            bootText = written.bootText,
                            activeProfileId = written.activeProfileId,
                            lockoutEnabled = dev.lockoutEnabled,
                            knownProfiles = written.knownProfiles,
                            language = baselineLanguage,
                            screenBrightness = baselineBrightness,
                            screenOffDelay = baselineDelay,
                            displayWindow = baselineDisplayWindow,
                            boltMode = baselineBoltMode,
                        )
                        // Adopt the clamped values into the EDITABLE device only if it has not been
                        // edited since this write was dispatched — otherwise a concurrent in-flight
                        // edit would be silently lost. Compare against the pre-clamp requested
                        // device (not the clamped `written`) so a firmware clamp is not mistaken
                        // for a concurrent edit. When an edit did land mid-write, keep it and only
                        // refresh the baseline so the "unsaved" badge stays honest.
                        val editedInFlight = requested != null && (
                            dev.bootText != requested.bootText ||
                                dev.activeProfileId != requested.activeProfileId ||
                                dev.language != requested.language ||
                                dev.screenBrightness != requested.screenBrightness ||
                                dev.screenOffDelay != requested.screenOffDelay ||
                                dev.displayWindow != requested.displayWindow ||
                                dev.boltMode != requested.boltMode ||
                                // A change of the source slots alone is not an edit.
                                !dev.knownProfiles.equalsIgnoringSourceSlots(requested.knownProfiles)
                            )
                        if (editedInFlight) {
                            // The board now holds the written profiles in new slots, so the edited
                            // profiles must point at those slots, or the next write sends each
                            // board-owned field back to the wrong slot.
                            dev.copy(
                                knownProfiles = dev.knownProfiles.withSourceSlotsAfterWrite(
                                    requested = requested.knownProfiles,
                                    writtenCount = written.knownProfiles.size,
                                ),
                                boardState = baseline,
                            )
                        } else {
                            dev.copy(
                                bootText = written.bootText,
                                activeProfileId = written.activeProfileId,
                                knownProfiles = written.knownProfiles,
                                language = baselineLanguage,
                                screenBrightness = baselineBrightness,
                                screenOffDelay = baselineDelay,
                                displayWindow = baselineDisplayWindow,
                                boltMode = baselineBoltMode,
                                boardState = baseline,
                            )
                        }
                    } else dev
                }
            }
            _writeErrors.update { it - written.id }
        } else if (error is BoardChangedBeforeWriteException) {
            // The board changed since the last read, so the engine did not write. The read result
            // that came first raises the conflict, and that dialog tells the user. A "save failed"
            // hint as well would say that a retry can help, which is not true until the user
            // makes a choice.
            log.d { "Board ${written.id} changed before the write; leaving local changes unsaved" }
            _writeErrors.update { it - written.id }
        } else {
            log.e(error) { "Board write failed for ${written.id}; leaving local changes unsaved" }
            _writeErrors.update { it + written.id }
        }
        // Tell the screen after the store and writeErrors hold the result, so both agree. A write
        // that the board change stopped reports nothing: the conflict tells the user, and a "Save
        // failed" message would say that a retry can help.
        if (savedByUser && error !is BoardChangedBeforeWriteException) {
            _saveResults.tryEmit(SaveResult(written.id, succeeded = error == null))
        }
    }

    override suspend fun connect(deviceId: String) {
        bluetoothRequestManager.send(BluetoothRequest.Connect(deviceId))
    }

    override suspend fun disconnect(deviceId: String) {
        bluetoothRequestManager.send(BluetoothRequest.Disconnect(deviceId))
    }

    override fun discardPendingChanges(deviceId: String) {
        val deviceUuid = Uuid.parse(deviceId)
        val device = deviceStore.knownDevices.value.firstOrNull { it.id == deviceUuid } ?: return
        val bs = device.boardState ?: return

        // Discarding clears the divergence, so any prior failed-write flag no longer applies.
        _writeErrors.update { it - deviceUuid }

        // Restore local configuration to match cached boardState. lockoutEnabled is a read-only
        // board fact, not an editable field, so discarding local edits leaves it untouched.
        //
        // The four board settings restore the baseline EXACTLY, with no fallback to the value the
        // device holds: a fallback would keep the very value the user asked to discard.
        deviceStore.update { current ->
            current.map { dev ->
                if (dev.id == deviceUuid) {
                    dev.copy(
                        bootText = bs.bootText,
                        activeProfileId = bs.activeProfileId,
                        knownProfiles = bs.knownProfiles,
                        language = bs.language,
                        screenBrightness = bs.screenBrightness,
                        screenOffDelay = bs.screenOffDelay,
                        displayWindow = bs.displayWindow,
                        boltMode = bs.boltMode,
                    )
                } else dev
            }
        }
    }

    companion object {
        private val log = Arbor.tag("DeviceViewModelDelegate")

        /** The time after which a write with no result no longer blocks Save. */
        internal const val WRITE_RESULT_TIMEOUT_MILLIS = 60_000L

        /** The save results that wait for a busy collector before the oldest is dropped. */
        private const val SAVE_RESULT_BUFFER = 8
    }
}

/**
 * The value to store after a write, for one of the board settings the board can refuse: the
 * language, the screen brightness and the screen-off delay.
 *
 * A null [written] value is positive evidence, not missing data: the engine reads the DeviceConfig
 * back after the write and falls back to the values it sent when that read fails, so a null here
 * means that the read SUCCEEDED and the board answered "unset". The app must record that, rather
 * than stamp the user's own value into the baseline and hide the refusal until the next connection
 * read cleared it with no message.
 *
 * The one case that keeps [baseline] is a write that sent the unset value itself ([requested] is
 * null): the board then kept its own value, and the baseline already holds it. When [requested]
 * is not null and [written] is null, the board refused the value, and the result is null.
 * A write result with no requested device gives a null [requested], so the baseline stays.
 */
internal fun <T> settledBoardValue(written: T?, requested: T?, baseline: T?): T? = when {
    written != null -> written
    requested != null -> null
    else -> baseline
}
