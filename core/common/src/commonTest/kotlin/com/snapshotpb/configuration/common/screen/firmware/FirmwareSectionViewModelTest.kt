package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cover for [FirmwareSectionViewModel]: the button and the reason of the Firmware section. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareSectionViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aBoardThatCanUpdateGetsAnActiveUpdateButton() {
        val viewModel = FirmwareSectionViewModel(BOARD, FakeFirmwareUpdateCoordinator(status()))

        val state = viewModel.state.value
        assertEquals(FirmwareSectionAction.UPDATE, state.action)
        assertNull(state.reason)
        assertEquals(boardVersion, state.status?.boardVersion)
        assertEquals(newVersion.toString(), state.status?.latest?.version)
    }

    @Test
    fun eachReasonGivesAnInactiveButtonAndTheReason() {
        FirmwareUnavailableReason.entries.forEach { reason ->
            val viewModel = FirmwareSectionViewModel(BOARD, FakeFirmwareUpdateCoordinator(status(reason = reason)))

            assertEquals(FirmwareSectionAction.NONE, viewModel.state.value.action, "$reason")
            assertEquals(reason, viewModel.state.value.reason, "$reason")
        }
    }

    @Test
    fun theUpdateOfThisBoardOpensTheUpdateScreen() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.UPDATE_IN_PROGRESS))
        val viewModel = FirmwareSectionViewModel(BOARD, coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Uploading(offset = 50, total = 100))

        val state = viewModel.state.value
        assertEquals(FirmwareSectionAction.SHOW_UPDATE, state.action)
        assertEquals(FirmwareStep.UPLOAD, state.activeStep)
        assertEquals(0.5f, state.activeFraction)
        assertNull(state.reason)
    }

    @Test
    fun theResultOfTheUpdateOfThisBoardStillOpensTheUpdateScreen() {
        // A failure that can retry must stay reachable: a retry after the reset only verifies.
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = FirmwareSectionViewModel(BOARD, coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NO_ADVERTISEMENT))

        assertEquals(FirmwareSectionAction.SHOW_UPDATE, viewModel.state.value.action)
        assertNull(viewModel.state.value.activeStep)
    }

    @Test
    fun theUpdateOfAnotherBoardGivesTheReasonOfTheStatus() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.UPDATE_IN_PROGRESS))
        val viewModel = FirmwareSectionViewModel(BOARD, coordinator)

        coordinator.report(OTHER_BOARD, FirmwareUpdateProgress.Verifying)

        assertEquals(FirmwareSectionAction.NONE, viewModel.state.value.action)
        assertEquals(FirmwareUnavailableReason.UPDATE_IN_PROGRESS, viewModel.state.value.reason)
        assertNull(viewModel.state.value.activeStep)
    }

    @Test
    fun aNewStatusChangesTheSection() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.CHECKING))
        val viewModel = FirmwareSectionViewModel(BOARD, coordinator)

        coordinator.setStatus(status())

        assertEquals(FirmwareSectionAction.UPDATE, viewModel.state.value.action)
    }

    @Test
    fun theRefreshReadsTheSiteAgain() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.CHECK_FAILED))
        val viewModel = FirmwareSectionViewModel(BOARD, coordinator)

        viewModel.refresh()

        assertEquals(listOf("refresh"), coordinator.calls)
    }

    @Test
    fun theSectionShowsTheCheckBeforeTheFirstStatus() {
        assertEquals(FirmwareUnavailableReason.CHECKING, FirmwareSectionState().reason)
    }
}
