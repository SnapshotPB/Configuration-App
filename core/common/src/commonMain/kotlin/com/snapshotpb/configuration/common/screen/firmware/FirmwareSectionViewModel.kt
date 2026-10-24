package com.snapshotpb.configuration.common.screen.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Firmware section of the configuration screen of the board [deviceId]. The section shows the
 * firmware status, and its button opens the update screen. The section does not start an update:
 * the update screen shows the warnings first.
 */
class FirmwareSectionViewModel(
    private val deviceId: String,
    private val coordinator: FirmwareUpdateCoordinator,
) : ViewModel() {

    val state: StateFlow<FirmwareSectionState> = combine(
        coordinator.status(deviceId),
        coordinator.progress,
        coordinator.deviceId,
    ) { status, progress, updatingId ->
        firmwareSectionState(deviceId, status, progress, updatingId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FirmwareSectionState())

    /** Reads the firmware site again. The section offers this after [FirmwareUnavailableReason.CHECK_FAILED]. */
    fun refresh() {
        log.d { "Read the firmware site again for $deviceId" }
        coordinator.refresh()
    }

    private companion object {
        private val log = Arbor.tag("FirmwareSectionViewModel")
    }
}
