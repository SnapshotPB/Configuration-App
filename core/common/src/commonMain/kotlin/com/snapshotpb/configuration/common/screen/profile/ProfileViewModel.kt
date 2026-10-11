package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Resolution state for the edited profile.
 *
 * The three cases exist because [DeviceStore.knownDevices] is a [kotlinx.coroutines.flow.StateFlow]
 * that answers `emptyList()` while the initial disk load is still in flight. A screen that cannot
 * tell that apart from "this profile was deleted" either strands on a spinner forever or reports a
 * bogus not-found — which is what the previous one-shot `firstOrNull()` read did on Android
 * saved-state restore, where the back stack is rebuilt against a freshly constructed store.
 */
sealed interface ProfileLoad {
    /** The store has not finished its initial disk load; nothing can be concluded yet. */
    data object Loading : ProfileLoad

    /** The store is loaded and holds no such device/profile (deleted, or a stale back-stack entry). */
    data object Missing : ProfileLoad

    data class Found(
        val profile: MarkerProfile,
        /** The board's last-read copy, or null when the device has never synced. Drives "unsaved" badges. */
        val boardProfile: MarkerProfile?,
    ) : ProfileLoad
}

class ProfileViewModel(
    private val args: Args,
    private val deviceStore: DeviceStore,
    private val deviceViewModelDelegate: DeviceViewModelDelegate,
) : ViewModel() {

    data class Args(
        val deviceId: String,
        val profileId: String,
    )

    /**
     * The profile as the editor has changed it, or `null` before the first [seedDraft].
     *
     * The draft lives here and not in the screen's `remember`. A rotation recreates the Android
     * activity and so drops all `remember` state, but this view model belongs to the nav entry
     * and outlives the activity. The draft goes when the user leaves the screen, and it is not
     * kept across process death: the store stays the one source of truth for saved profiles.
     * Compose state, and not a StateFlow, so a text field sees each edit in the same frame.
     */
    var draft: MarkerProfile? by mutableStateOf(null)
        private set

    /** Sets the draft from the store, but only one time, so a later store write cannot clobber edits. */
    fun seedDraft(profile: MarkerProfile) {
        if (draft == null) draft = profile
    }

    /** Replaces the draft with an edited copy. */
    fun editDraft(profile: MarkerProfile) {
        draft = profile
    }

    fun devicePhotoPath(): Flow<String> =
        deviceStore.knownDevices.map { devices ->
            devices.firstOrNull { it.id.toString() == args.deviceId }?.photoPath.orEmpty()
        }

    /**
     * Emits the profile as the store resolves it. The screen seeds the [draft] from the first
     * [ProfileLoad.Found] and ignores later emissions, so a background store write cannot
     * clobber in-progress edits.
     */
    fun profileLoad(): Flow<ProfileLoad> =
        combine(deviceStore.isLoaded, deviceStore.knownDevices) { isLoaded, devices ->
            val device = devices.firstOrNull { it.id.toString() == args.deviceId }
            val profile = device?.knownProfiles?.firstOrNull { it.id.toString() == args.profileId }
            when {
                device != null && profile != null -> ProfileLoad.Found(
                    profile = profile,
                    boardProfile = device.boardState
                        ?.knownProfiles
                        ?.firstOrNull { it.id.toString() == args.profileId },
                )
                isLoaded -> ProfileLoad.Missing
                else -> ProfileLoad.Loading
            }
        }

    /**
     * Commits the edited profile through the shared delegate, so the phone and the watch apply
     * the same store rules.
     */
    fun updateProfile(updatedProfile: MarkerProfile) {
        deviceViewModelDelegate.updateProfile(args.deviceId, updatedProfile)
    }
}
