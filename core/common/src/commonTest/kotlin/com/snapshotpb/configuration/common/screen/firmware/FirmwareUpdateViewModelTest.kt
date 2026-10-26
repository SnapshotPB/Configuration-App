package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cover for [FirmwareUpdateViewModel]: the phase of each coordinator step, and the rules that
 * permit a start, a retry, a cancel and a close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateViewModelTest {

    @BeforeTest
    fun setUp() {
        // The view model builds a StateFlow on viewModelScope, which uses the main dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(coordinator: FakeFirmwareUpdateCoordinator) = FirmwareUpdateViewModel(
        deviceId = BOARD,
        coordinator = coordinator,
        deviceStore = testDeviceStore(UnconfinedTestDispatcher()),
    )

    @Test
    fun aBoardThatCanUpdateGetsTheConfirmPhaseAndStarts() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        assertEquals(FirmwareUpdatePhase.Confirm(reason = null), viewModel.state.value.phase)
        assertTrue((viewModel.state.value.phase as FirmwareUpdatePhase.Confirm).canStart)

        viewModel.start()

        assertEquals(listOf("start:$BOARD"), coordinator.calls)
    }

    @Test
    fun aBoardThatCannotUpdateDoesNotStart() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.NOT_CONNECTED))
        val viewModel = viewModel(coordinator)

        assertEquals(
            FirmwareUpdatePhase.Confirm(reason = FirmwareUnavailableReason.NOT_CONNECTED),
            viewModel.state.value.phase,
        )
        viewModel.start()

        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun theUpdateOfAnotherBoardShowsTheConfirmPhaseWithItsReason() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.UPDATE_IN_PROGRESS))
        val viewModel = viewModel(coordinator)

        coordinator.report(OTHER_BOARD, FirmwareUpdateProgress.Uploading(offset = 10, total = 100))

        assertEquals(
            FirmwareUpdatePhase.Confirm(reason = FirmwareUnavailableReason.UPDATE_IN_PROGRESS),
            viewModel.state.value.phase,
        )
        assertFalse(viewModel.state.value.isRunning)
        viewModel.cancel()
        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun aDownloadShowsItsFractionAndCanStop() {
        val coordinator = FakeFirmwareUpdateCoordinator(status(reason = FirmwareUnavailableReason.UPDATE_IN_PROGRESS))
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Downloading(received = 50, total = 200))

        assertEquals(
            FirmwareUpdatePhase.Running(
                step = FirmwareStep.DOWNLOAD,
                fraction = 0.25f,
                cancelEffect = FirmwareCancelEffect.STOPS_UPDATE,
            ),
            viewModel.state.value.phase,
        )
        assertTrue(viewModel.state.value.isRunning)

        viewModel.start()
        viewModel.cancel()

        assertEquals(listOf("cancel"), coordinator.calls)
    }

    @Test
    fun anUploadShowsItsFraction() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Uploading(offset = 300, total = 400))

        val phase = viewModel.state.value.phase as FirmwareUpdatePhase.Running
        assertEquals(FirmwareStep.UPLOAD, phase.step)
        assertEquals(0.75f, phase.fraction)
    }

    @Test
    fun theResetOffersNoStop() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Resetting)
        viewModel.cancel()

        assertEquals(
            FirmwareUpdatePhase.Running(step = FirmwareStep.RESET, cancelEffect = null),
            viewModel.state.value.phase,
        )
        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun theSwapShowsTheTimesAndAStopOnlyStopsTheWait() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.AwaitingSwap(elapsedMillis = 30_000, expectedMillis = 90_000))

        assertEquals(
            FirmwareUpdatePhase.Running(
                step = FirmwareStep.SWAP,
                elapsedMillis = 30_000,
                expectedMillis = 90_000,
                cancelEffect = FirmwareCancelEffect.STOPS_WAIT,
            ),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun aFailureMarksTheLastStepAndCanRetry() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Checking)
        coordinator.report(BOARD, FirmwareUpdateProgress.Uploading(offset = 10, total = 100))
        coordinator.report(BOARD, FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.LINK_LOST, "Timeout"))

        val phase = viewModel.state.value.phase
        assertEquals(FirmwareUpdatePhase.Failed(FirmwareUpdateFailure.LINK_LOST, "Timeout", FirmwareStep.UPLOAD), phase)
        assertTrue((phase as FirmwareUpdatePhase.Failed).canRetry)

        viewModel.retry()

        assertEquals(listOf("retry"), coordinator.calls)
    }

    @Test
    fun aFailureThatCannotRetryOffersNoRetry() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NO_RELEASE))
        viewModel.retry()

        val phase = viewModel.state.value.phase as FirmwareUpdatePhase.Failed
        assertFalse(phase.canRetry)
        // The screen did not see a running step.
        assertEquals(null, phase.step)
        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun theDoneStepShowsTheVersionAndTheCloseDismissesIt() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Done(newVersion))

        assertEquals(FirmwareUpdatePhase.Done(newVersion), viewModel.state.value.phase)
        assertTrue(viewModel.close())
        assertEquals(listOf("dismiss"), coordinator.calls)
    }

    @Test
    fun aCloseDuringTheUpdateDoesNotDismissIt() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Verifying)

        assertFalse(viewModel.close())
        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun aCloseBeforeTheStartDoesNotDismiss() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        assertTrue(viewModel.close())
        assertEquals(emptyList(), coordinator.calls)
    }

    @Test
    fun theIdleStepClearsTheLastStep() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Verifying)
        coordinator.report(BOARD, FirmwareUpdateProgress.Idle)
        coordinator.report(BOARD, FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NETWORK))

        assertEquals(null, (viewModel.state.value.phase as FirmwareUpdatePhase.Failed).step)
    }

    @Test
    fun anotherBoardClearsTheLastStep() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        val viewModel = viewModel(coordinator)

        coordinator.report(BOARD, FirmwareUpdateProgress.Verifying)
        coordinator.report(OTHER_BOARD, FirmwareUpdateProgress.Checking)
        // The step changes first, then the board.
        coordinator.progress.value = FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NETWORK)
        coordinator.deviceId.value = BOARD

        assertEquals(null, (viewModel.state.value.phase as FirmwareUpdatePhase.Failed).step)
    }

    @Test
    fun theFirstStateShowsAnUpdateThatRunsAlready() {
        val coordinator = FakeFirmwareUpdateCoordinator(status())
        coordinator.report(BOARD, FirmwareUpdateProgress.Uploading(offset = 1, total = 4))

        val viewModel = viewModel(coordinator)

        assertEquals(FirmwareStep.UPLOAD, (viewModel.state.value.phase as FirmwareUpdatePhase.Running).step)
    }

    @Test
    fun theNameOfTheBoardComesFromTheStore() = runTest {
        val store = testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(testDevice(name = "Ego")))
        store.awaitLoaded()
        val viewModel = FirmwareUpdateViewModel(
            deviceId = BOARD,
            coordinator = FakeFirmwareUpdateCoordinator(status()),
            deviceStore = store,
        )

        assertEquals("Ego", viewModel.deviceName.first())
    }
}
