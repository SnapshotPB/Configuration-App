package com.snapshotpb.configuration.common.screen.log

import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEntry
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSource
import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import com.snapshotpb.configuration.common.share.ShareRequest
import com.snapshotpb.configuration.common.share.ShareRequestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothLogViewModelTest {

    private val fixedNow = Instant.parse("2026-11-20T10:11:12.345Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stateFollowsLogSnapshot() {
        val fake = FakeBluetoothLog()
        val viewModel = BluetoothLogViewModel(
            bluetoothLog = fake,
            shareRequestManager = ShareRequestManager(),
            clock = fixedClock,
            appVersion = APP_VERSION,
        )
        val snapshot = BluetoothLogSnapshot(
            entries = listOf(
                BluetoothLogEntry(
                    sequence = 1L,
                    timeMillis = fixedNow.toEpochMilliseconds(),
                    session = 1,
                    source = BluetoothLogSource.BOARD,
                    text = "bt: ready",
                ),
            ),
            boardLogState = BoardLogState.RECEIVING,
        )

        fake.flow.value = snapshot

        assertEquals(snapshot, viewModel.state.value)
    }

    @Test
    fun clearCallsLogOnce() {
        val fake = FakeBluetoothLog()
        val viewModel = BluetoothLogViewModel(
            bluetoothLog = fake,
            shareRequestManager = ShareRequestManager(),
            clock = fixedClock,
            appVersion = APP_VERSION,
        )

        viewModel.clear()

        assertEquals(1, fake.clearCalls)
    }

    @Test
    fun exportSendsFormattedTextRequest() = runTest {
        val snapshot = BluetoothLogSnapshot(
            entries = listOf(
                BluetoothLogEntry(
                    sequence = 1L,
                    timeMillis = fixedNow.toEpochMilliseconds(),
                    session = 1,
                    source = BluetoothLogSource.APP,
                    text = "connect.request",
                ),
            ),
            boardLogState = BoardLogState.REQUESTED,
        )
        val shareRequestManager = ShareRequestManager()
        val requests = mutableListOf<ShareRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shareRequestManager.bus.collect { requests.add(it) }
        }
        val viewModel = BluetoothLogViewModel(
            bluetoothLog = FakeBluetoothLog(snapshot),
            shareRequestManager = shareRequestManager,
            clock = fixedClock,
            appVersion = APP_VERSION,
            formatDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        viewModel.export("Title")

        assertEquals(1, requests.size)
        val request = requests.single() as ShareRequest.Text
        assertEquals("snapshotpb-bluetooth-log-20261120-101112.txt", request.fileName)
        assertEquals("Title", request.title)
        assertEquals("text/plain", request.mimeType)
        assertEquals(BluetoothLogExport.format(snapshot, APP_VERSION, fixedNow), request.text)
    }

    @Test
    fun canExportTracksShareBusCollectors() = runTest {
        val shareRequestManager = ShareRequestManager()
        val viewModel = BluetoothLogViewModel(
            bluetoothLog = FakeBluetoothLog(),
            shareRequestManager = shareRequestManager,
            clock = fixedClock,
            appVersion = APP_VERSION,
            formatDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(viewModel.canExport.value)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shareRequestManager.bus.collect {}
        }

        assertTrue(viewModel.canExport.value)
    }

    @Test
    fun exportIgnoresSecondCallWhileFirstWaits() = runTest {
        val shareRequestManager = ShareRequestManager()
        val requests = mutableListOf<ShareRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shareRequestManager.bus.collect { requests.add(it) }
        }
        val viewModel = BluetoothLogViewModel(
            bluetoothLog = FakeBluetoothLog(),
            shareRequestManager = shareRequestManager,
            clock = fixedClock,
            appVersion = APP_VERSION,
            formatDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.export("Title")
        viewModel.export("Title")
        advanceUntilIdle()

        assertEquals(1, requests.size)
    }

    private companion object {
        private const val APP_VERSION = "1.2.3"
    }
}
