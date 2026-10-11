package com.snapshotpb.configuration.common.screen.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.limitBootText
import com.snapshotpb.configuration.model.limitDeviceName
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/** The unsaved edits of the device configuration screen. */
data class ConfigureDeviceDraft(
    val deviceName: String = "",
    val bootText: String = "",
    val activeProfileId: Uuid? = null,
    val language: BoardLanguage? = null,
    val screenBrightness: BoardScreenBrightness? = null,
    val screenOffDelay: Int? = null,
    val displayWindow: DisplayWindow? = null,
    /**
     * Whether the user chose the custom display window entry. It is kept apart from the window,
     * because a custom rectangle that equals a known frame must keep the custom entry selected.
     */
    val displayWindowCustom: Boolean = false,
    /** Whether the draft has taken its first values from the stored device. */
    val hydrated: Boolean = false,
)

/**
 * The draft after the store reports [loaded]. The first device fills the whole draft. A board
 * read can finish after the screen opens, so a later device fills only a board setting that is
 * still empty: a choice the user made on this screen stays.
 */
internal fun ConfigureDeviceDraft.hydratedFrom(loaded: Device?): ConfigureDeviceDraft = when {
    loaded == null -> this
    !hydrated -> copy(
        deviceName = loaded.name.limitDeviceName(),
        bootText = loaded.bootText.limitBootText(),
        activeProfileId = loaded.activeProfileId,
        language = loaded.language,
        screenBrightness = loaded.screenBrightness,
        screenOffDelay = loaded.screenOffDelay,
        displayWindow = loaded.displayWindow,
        hydrated = true,
    )
    else -> copy(
        language = language ?: loaded.language,
        screenBrightness = screenBrightness ?: loaded.screenBrightness,
        screenOffDelay = screenOffDelay ?: loaded.screenOffDelay,
        displayWindow = displayWindow ?: loaded.displayWindow,
    )
}

class ConfigureDeviceViewModel(
    private val deviceId: String,
    private val deviceStore: DeviceStore,
    deviceViewModelDelegate: DeviceViewModelDelegate,
) : ViewModel(), DeviceViewModelDelegate by deviceViewModelDelegate {

    /**
     * The unsaved edits. They live here and not in the screen's `remember`: a rotation recreates
     * the Android activity and drops all `remember` state, but this view model belongs to the nav
     * entry and outlives the activity. The edits go when the user leaves the screen.
     */
    var draft: ConfigureDeviceDraft by mutableStateOf(ConfigureDeviceDraft())
        private set

    fun deviceFlow(): Flow<Device?> =
        deviceStore.knownDevices.map { devices ->
            devices.firstOrNull { it.id.toString() == deviceId }
        }

    /** Takes values from the stored device into the draft (see [hydratedFrom]). */
    fun onDeviceLoaded(device: Device?) {
        draft = draft.hydratedFrom(device)
    }

    /** Applies an edit to the draft. */
    fun editDraft(transform: (ConfigureDeviceDraft) -> ConfigureDeviceDraft) {
        draft = transform(draft)
    }

    /** Stores the draft through the shared delegate. */
    fun saveDraft() {
        val current = draft
        updateDeviceConfiguration(
            deviceId = deviceId,
            name = current.deviceName,
            bootText = current.bootText,
            activeProfileId = current.activeProfileId,
            language = current.language,
            screenBrightness = current.screenBrightness,
            screenOffDelay = current.screenOffDelay,
            displayWindow = current.displayWindow,
        )
    }
}
