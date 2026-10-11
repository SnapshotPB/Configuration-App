package com.snapshotpb.configuration.common.screen.profile

import androidx.lifecycle.ViewModel
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.limitProfileName
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileAddViewModel(
    private val deviceId: String,
    private val deviceStore: DeviceStore,
) : ViewModel() {

    /**
     * Emits the device as the store resolves it, `null` until then.
     *
     * Deliberately reactive rather than a one-shot read: [DeviceStore.knownDevices] answers
     * `emptyList()` while the initial disk load is in flight, so the old
     * `firstOrNull() ?: error(...)` threw from inside the screen's `LaunchedEffect` — an
     * unrecoverable crash — whenever this screen composed before the load landed (routinely, on
     * Android saved-state restore). The screen gates its Create button on a non-null device
     * instead.
     */
    fun deviceFlow(): Flow<Device?> =
        deviceStore.knownDevices.map { devices ->
            devices.firstOrNull { device -> deviceId == device.id.toString() }
        }

    /**
     * Appends a profile and returns its id, or `null` when the device is no longer in the store
     * (deleted while this screen was open) — the caller must not navigate to a profile that was
     * never created.
     */
    fun addProfile(name: String): String? {
        val device = deviceStore.knownDevices.value.firstOrNull { it.id.toString() == deviceId }
            ?: return null
        // The new profile takes the next slot, and its id is the one a board read derives for
        // that slot (see withSlotProfileIds), so a reconnect after a save raises no conflict.
        val newProfileId = getProfileUuid(device.id, device.knownProfiles.size)
        // Same cap the editor and the board-write clamp apply, so a name can never be created here
        // that the wire encoding would silently truncate into a permanent divergence.
        // It has no source slot (MarkerProfile.sourceSlot): the board holds no value for it yet.
        val newProfile = MarkerProfile(id = newProfileId, name = name.limitProfileName())
        deviceStore.update { current ->
            current.map { known ->
                if (known.id == device.id) {
                    known.copy(knownProfiles = known.knownProfiles + newProfile).withSlotProfileIds()
                } else {
                    known
                }
            }
        }
        return newProfileId.toString()
    }
}
