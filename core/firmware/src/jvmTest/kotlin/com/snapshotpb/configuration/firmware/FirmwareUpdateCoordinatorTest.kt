package com.snapshotpb.configuration.firmware

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.BoardImage
import com.snapshotpb.configuration.bluetooth.BoardUpdateService
import com.snapshotpb.configuration.bluetooth.FirmwareUploadState
import com.snapshotpb.configuration.bluetooth.UpdateServiceStatus
import com.snapshotpb.configuration.firmware.FakeFirmwareSite.Reply
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [FirmwareUpdateCoordinatorImpl] over a [FakeUpdateBoard] that answers the Bluetooth requests
 * as the engine does, and over the fixture site of [FakeFirmwareSite] (release 2026.52.1 of
 * `snapshot_v2`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateCoordinatorTest {

    /**
     * A board behind the request bus. It reports its update service after each connect and answers
     * the upload and the confirm. After the fake reset it is off the air for [SWAP_MILLIS], then it
     * runs the image of [swapResult].
     */
    private class FakeUpdateBoard(
        val stateManager: BluetoothStateManager,
        val devices: MutableStateFlow<BluetoothDeviceState>,
    ) {
        var target: HardwareTarget? = HardwareTarget("snapshot_v2", qualifiers = "esp32c3")
        var status = UpdateServiceStatus.AVAILABLE
        var version = FirmwareVersion(2026, 43, 1, dev = false)
        var hash = OLD_HASH
        var confirmed = true

        /** True while the board advertises and accepts a connect. */
        var onAir = true

        /** What the board runs after the swap: the new image, the old one, or none (the radio stays off). */
        var swapResult = SwapResult.NEW_IMAGE

        /** When set, the upload fails with this reason. */
        var uploadFailure: FirmwareUpdateFailure? = null

        /** True when the new image confirms itself at boot (option A of decision 4). */
        var confirmsItself = false

        /** The number of later connects whose SMP read fails, as on a board that answers late. */
        var failedReads = 0

        /** When set, `image list` fails on each read. */
        var noImageList = false

        val requests = mutableListOf<BluetoothRequest>()

        fun connectNow() {
            val service = if (failedReads > 0) {
                failedReads--
                BoardUpdateService(UpdateServiceStatus.FAILED)
            } else {
                service()
            }
            stateManager.recordUpdateService(BOARD_ID, service)
            stateManager.deviceConnected(BOARD_ID)
            advertise()
        }

        fun advertise() {
            devices.value = BluetoothDeviceState(
                advertisementData = mapOf(BOARD_ID to AdvertisementData("Cocker", version.toString(), firmwareVersion = version)),
            )
        }

        private fun service() = BoardUpdateService(
            status = status,
            target = target,
            bufferSize = 2475,
            activeImage = BoardImage("${version.year - 2000}.${version.week}.${version.count}", hash, confirmed)
                .takeUnless { noImageList },
        )

        private fun disconnect() {
            stateManager.clearUpdateService(BOARD_ID)
            stateManager.deviceDisconnected(BOARD_ID)
        }

        suspend fun handle(scope: CoroutineScope, request: BluetoothRequest) {
            requests += request
            when (request) {
                is BluetoothRequest.Connect -> scope.launch {
                    stateManager.update(BluetoothState.Connecting(request.deviceId))
                    delay(CONNECT_DELAY_MILLIS)
                    if (onAir) connectNow()
                    stateManager.update(BluetoothState.Ready)
                }
                is BluetoothRequest.Disconnect -> disconnect()
                is BluetoothRequest.UpdateFirmware -> scope.launch { upload(scope, request) }
                is BluetoothRequest.ConfirmFirmware -> {
                    confirmed = true
                    stateManager.recordUpdateService(BOARD_ID, service())
                    report(request.requestId, FirmwareUpdateProgress.Done(version))
                }
                else -> Unit
            }
        }

        private suspend fun upload(scope: CoroutineScope, request: BluetoothRequest.UpdateFirmware) {
            report(request.requestId, FirmwareUpdateProgress.Preparing)
            val total = request.image.bytes.size.toLong()
            delay(UPLOAD_DELAY_MILLIS)
            report(request.requestId, FirmwareUpdateProgress.Uploading(total / 2, total))
            delay(UPLOAD_DELAY_MILLIS)
            uploadFailure?.let { reason ->
                report(request.requestId, FirmwareUpdateProgress.Failed(reason))
                return
            }
            report(request.requestId, FirmwareUpdateProgress.Uploading(total, total))
            report(request.requestId, FirmwareUpdateProgress.AwaitingSwap(), NEW_HASH)
            disconnect()
            onAir = false
            val newVersion = assertNotNull(request.image.version)
            scope.launch {
                delay(SWAP_MILLIS)
                when (swapResult) {
                    SwapResult.NEW_IMAGE -> {
                        version = newVersion
                        hash = NEW_HASH
                        confirmed = confirmsItself
                        onAir = true
                        advertise()
                    }
                    SwapResult.OLD_IMAGE -> onAir = true
                    SwapResult.RADIO_OFF -> Unit
                }
            }
        }

        private fun report(requestId: Long, progress: FirmwareUpdateProgress, hash: String? = null) {
            stateManager.updateFirmwareUpload(FirmwareUploadState(requestId, BOARD_ID, progress, hash))
        }
    }

    private enum class SwapResult { NEW_IMAGE, OLD_IMAGE, RADIO_OFF }

    private class Fixture(
        val coordinator: FirmwareUpdateCoordinatorImpl,
        val board: FakeUpdateBoard,
        val site: FakeFirmwareSite,
        val feed: FirmwareFeed,
        val steps: List<FirmwareUpdateProgress>,
    )

    private fun TestScope.newFixture(policy: FirmwareUpdatePolicy = FirmwareUpdatePolicy(debugBuild = true)): Fixture {
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val requestManager = BluetoothRequestManager()
        val devices = MutableStateFlow(BluetoothDeviceState())
        val board = FakeUpdateBoard(stateManager, devices)
        val site = FakeFirmwareSite()
        val feed = FirmwareFeed(http = site, hashDispatcher = StandardTestDispatcher(testScheduler))
        backgroundScope.launch { requestManager.bus.collect { board.handle(backgroundScope, it) } }
        val coordinator = FirmwareUpdateCoordinatorImpl(
            feed = { feed },
            policy = policy,
            bluetoothRequestManager = requestManager,
            bluetoothStateManager = stateManager,
            deviceStateSource = BluetoothDeviceStateSource { devices },
            scope = backgroundScope,
        )
        val steps = mutableListOf<FirmwareUpdateProgress>()
        backgroundScope.launch { coordinator.progress.collect { steps += it } }
        runCurrent()
        return Fixture(coordinator, board, site, feed, steps)
    }

    /**
     * Runs the work of the coordinator and of the board. Their coroutines are on `backgroundScope`,
     * which `advanceUntilIdle` does not run, so this advances the virtual time past the longest flow.
     */
    private fun TestScope.runAll() {
        advanceTimeBy(FLOW_MILLIS)
        runCurrent()
    }

    private suspend fun Fixture.status(): FirmwareStatus =
        coordinator.status(BOARD_ID).first { it.reason != FirmwareUnavailableReason.CHECKING }

    private fun Fixture.uploads() = board.requests.filterIsInstance<BluetoothRequest.UpdateFirmware>()

    private fun Fixture.confirms() = board.requests.filterIsInstance<BluetoothRequest.ConfirmFirmware>()

    @Test
    fun status_offersANewerReleaseForTheTargetOfTheBoard() = runTest {
        val f = newFixture()
        f.board.connectNow()

        val status = f.status()

        assertTrue(status.canUpdate)
        assertEquals("2026.52.1", status.latest?.version)
        assertEquals(FirmwareVersion(2026, 43, 1, dev = false), status.boardVersion)
        assertEquals("snapshot_v2", status.target?.board)
        assertTrue(status.connected)
    }

    @Test
    fun status_offersTheReleaseToADevBuildWithTheSameNumbers() = runTest {
        val f = newFixture()
        f.board.version = FirmwareVersion(2026, 52, 1, dev = true)
        f.board.connectNow()

        val status = f.status()

        // The advertisement has the dev flag, and it has the numbers of the running image.
        assertEquals(FirmwareVersion(2026, 52, 1, dev = true), status.boardVersion)
        assertTrue(status.canUpdate)
    }

    @Test
    fun status_givesNoOfferToABoardOnTheNewestRelease() = runTest {
        val f = newFixture()
        f.board.version = FirmwareVersion(2026, 52, 1, dev = false)
        f.board.connectNow()

        assertEquals(FirmwareUnavailableReason.UP_TO_DATE, f.status().reason)
    }

    @Test
    fun status_givesNoOfferForAnotherTarget() = runTest {
        val f = newFixture()
        f.board.target = HardwareTarget("snapshot_reva", qualifiers = "esp32c3")
        f.board.connectNow()

        // The site has no file for snapshot_reva.
        val status = f.status()

        assertEquals(FirmwareUnavailableReason.NO_RELEASE, status.reason)
        assertNull(status.latest)
    }

    @Test
    fun status_givesNoOfferWhenTheReleaseIsNotForTheHardware() = runTest {
        val f = newFixture()
        f.site.replies["$FIRMWARE_SITE_URL/snapshot_v2/latest.json"] = Reply.Text(
            FakeFirmwareSite.fixtureText("snapshot_v2/latest.json")
                .replace("\"compatible_hardware\": [\n    \"snapshot_v2\"\n  ]", "\"compatible_hardware\": []")
        )
        f.board.connectNow()

        assertEquals(FirmwareUnavailableReason.INCOMPATIBLE_HARDWARE, f.status().reason)
    }

    @Test
    fun status_givesNoOfferToABoardWithoutTheUpdateService() = runTest {
        val f = newFixture()
        f.board.status = UpdateServiceStatus.ABSENT
        f.board.target = null
        f.board.connectNow()

        val status = f.status()

        assertEquals(FirmwareUnavailableReason.NO_UPDATE_SERVICE, status.reason)
        assertTrue(f.site.requests.isEmpty(), "a board with no target needs no read of the site")
    }

    @Test
    fun status_ofADisconnectedBoardIsNotConnected() = runTest {
        val f = newFixture()

        assertEquals(FirmwareUnavailableReason.NOT_CONNECTED, f.status().reason)
    }

    @Test
    fun status_whenTheSiteFails_isCheckFailed_andRefreshReadsItAgain() = runTest {
        val f = newFixture()
        f.site.replies["$FIRMWARE_SITE_URL/snapshot_v2/latest.json"] = Reply.Status(503)
        f.board.connectNow()
        assertEquals(FirmwareUnavailableReason.CHECK_FAILED, f.status().reason)

        f.site.replies.clear()
        f.coordinator.refresh()
        runAll()

        assertTrue(f.status().canUpdate)
    }

    @Test
    fun releaseGate_aReleaseBuildShowsTheStatusButOffersNoUpdate() = runTest {
        val f = newFixture(policy = FirmwareUpdatePolicy())
        f.board.connectNow()

        val status = f.status()
        f.coordinator.start(BOARD_ID)
        runAll()

        assertEquals(FirmwareUnavailableReason.NOT_YET_RELEASED, status.reason)
        assertEquals("2026.52.1", status.latest?.version)
        assertEquals(FirmwareUpdateProgress.Idle, f.coordinator.progress.value)
        assertTrue(f.uploads().isEmpty())
    }

    @Test
    fun releaseGate_aLocalSiteOpensTheGate() = runTest {
        assertTrue(FirmwareUpdatePolicy(siteUrl = "http://localhost:8000").updatesEnabled)
        assertTrue(FirmwareUpdatePolicy(debugBuild = true).updatesEnabled)
        assertEquals(FirmwareUpdatePolicy.UPDATES_RELEASED, FirmwareUpdatePolicy(siteUrl = "$FIRMWARE_SITE_URL/").updatesEnabled)
    }

    @Test
    fun update_runsTheWholeFlowAndConfirmsTheNewImage() = runTest {
        val f = newFixture()
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()

        assertEquals(FirmwareUpdateProgress.Done(FirmwareVersion(2026, 52, 1, dev = false)), f.coordinator.progress.value)
        assertEquals(BOARD_ID, f.coordinator.deviceId.value)
        assertEquals(1, f.uploads().size)
        assertEquals(1, f.confirms().size, "the image is still in test, so the app confirms it")
        assertTrue(f.board.confirmed)
        // A StateFlow collector can miss a short step (the check reads the site with no wait here),
        // so the test compares the steps that each run must show, in order.
        val kinds = f.steps.map { it::class }.distinct().filter { it != FirmwareUpdateProgress.Checking::class }
        assertEquals(
            listOf(
                FirmwareUpdateProgress.Idle::class,
                FirmwareUpdateProgress.Downloading::class,
                FirmwareUpdateProgress.Preparing::class,
                FirmwareUpdateProgress.Uploading::class,
                FirmwareUpdateProgress.AwaitingSwap::class,
                FirmwareUpdateProgress.Verifying::class,
                FirmwareUpdateProgress.Done::class,
            ),
            kinds,
        )
        val swap = f.steps.filterIsInstance<FirmwareUpdateProgress.AwaitingSwap>().first()
        assertEquals(FirmwareUpdateCoordinator.EXPECTED_SWAP_MILLIS, swap.expectedMillis)
        assertTrue(f.board.requests.contains(BluetoothRequest.StartScan))

        // The download is no longer in memory: a new download reads the site again.
        val imageRequests = f.site.requests.count { it.endsWith(".bin") }
        f.feed.download(assertNotNull(f.feed.latest("snapshot_v2")))
        assertEquals(imageRequests + 1, f.site.requests.count { it.endsWith(".bin") })

        // After the update the status shows the new version.
        assertEquals(FirmwareUnavailableReason.UP_TO_DATE, f.status().reason)
    }

    @Test
    fun update_whenTheFirmwareConfirmedItself_sendsNoConfirm() = runTest {
        val f = newFixture()
        f.board.confirmsItself = true
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()

        assertIs<FirmwareUpdateProgress.Done>(f.coordinator.progress.value)
        assertTrue(f.confirms().isEmpty())
    }

    @Test
    fun update_withABadHash_stopsBeforeTheUpload() = runTest {
        val f = newFixture()
        f.site.replies["$FIRMWARE_SITE_URL/snapshot_v2/2026.52.1/snapshot_v2-2026.52.1-app.signed.bin"] =
            Reply.Bytes(ByteArray(43))
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.coordinator.progress.value)
        assertEquals(FirmwareUpdateFailure.BAD_IMAGE, failed.reason)
        assertTrue(f.uploads().isEmpty(), "no byte of a bad image goes to the board")
    }

    @Test
    fun update_whenTheBoardIsNotConnected_failsWithNotConnected() = runTest {
        val f = newFixture()

        f.coordinator.start(BOARD_ID)
        runAll()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NOT_CONNECTED), f.coordinator.progress.value)
    }

    @Test
    fun verify_withTheOldImage_reportsARevert() = runTest {
        val f = newFixture()
        f.board.swapResult = SwapResult.OLD_IMAGE
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.REVERTED), f.coordinator.progress.value)
        assertTrue(f.confirms().isEmpty(), "the app does not confirm the old image")
    }

    @Test
    fun verify_withNoAdvertisement_fails_andARetryOnlyVerifies() = runTest {
        val f = newFixture()
        f.board.swapResult = SwapResult.RADIO_OFF
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.coordinator.progress.value)
        assertEquals(FirmwareUpdateFailure.NO_ADVERTISEMENT, failed.reason)
        assertTrue(failed.reason.canRetry)
        val connects = f.board.requests.count { it is BluetoothRequest.Connect }
        assertTrue(connects > 1, "the verify step tries the connect again within its time limit")

        // The user turns the radio on in the board menu, and taps "try again".
        f.board.version = FirmwareVersion(2026, 52, 1, dev = false)
        f.board.hash = NEW_HASH
        f.board.confirmed = false
        f.board.onAir = true
        f.coordinator.retry()
        runAll()

        assertIs<FirmwareUpdateProgress.Done>(f.coordinator.progress.value)
        assertEquals(1, f.uploads().size, "a retry after the reset does not upload again")
        assertEquals(1, f.confirms().size)
    }

    @Test
    fun verify_whenTheFirstReadAfterTheResetFails_connectsAgain() = runTest {
        val f = newFixture()
        f.board.connectNow()
        f.board.failedReads = 1

        f.coordinator.start(BOARD_ID)
        runAll()

        assertIs<FirmwareUpdateProgress.Done>(f.coordinator.progress.value)
        assertEquals(2, f.board.requests.count { it is BluetoothRequest.Connect })
        assertEquals(1, f.confirms().size)
    }

    @Test
    fun verify_withNoImageList_failsWithARetry_andReportsNoDone() = runTest {
        val f = newFixture()
        f.board.connectNow()
        f.board.noImageList = true

        f.coordinator.start(BOARD_ID)
        runAll()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.coordinator.progress.value)
        assertEquals(FirmwareUpdateFailure.BOARD_REFUSED, failed.reason)
        assertEquals("NoImageList", failed.detail)
        assertTrue(failed.reason.canRetry)
        assertTrue(f.confirms().isEmpty())
    }

    @Test
    fun cancel_thenAStartAtOnce_doesNotOverwriteTheCancel() = runTest {
        val f = newFixture()
        f.board.connectNow()
        f.coordinator.start(BOARD_ID)
        f.coordinator.progress.first { it is FirmwareUpdateProgress.Uploading }

        f.coordinator.cancel()
        f.coordinator.start(BOARD_ID)
        runAll()

        // The start came while the cancel waited for the run to end, so it did nothing.
        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.CANCELLED), f.coordinator.progress.value)
        assertEquals(1, f.uploads().size)
    }

    @Test
    fun upload_whenTheLinkDrops_fails_andARetryUploadsAgain() = runTest {
        val f = newFixture()
        f.board.uploadFailure = FirmwareUpdateFailure.LINK_LOST
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        runAll()
        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.LINK_LOST), f.coordinator.progress.value)

        f.board.uploadFailure = null
        f.coordinator.retry()
        runAll()

        assertIs<FirmwareUpdateProgress.Done>(f.coordinator.progress.value)
        assertEquals(2, f.uploads().size)
        assertEquals(1, f.site.requests.count { it.endsWith(".bin") }, "the second attempt uses the image in memory")
    }

    @Test
    fun cancel_duringTheUpload_disconnectsTheBoard() = runTest {
        val f = newFixture()
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        // Run until the board reports the first chunk.
        f.coordinator.progress.first { it is FirmwareUpdateProgress.Uploading }
        f.coordinator.cancel()
        runAll()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.CANCELLED), f.coordinator.progress.value)
        assertTrue(f.board.requests.contains(BluetoothRequest.Disconnect(BOARD_ID)))
        assertTrue(f.board.requests.none { it is BluetoothRequest.Connect }, "a cancelled update does not verify")
    }

    @Test
    fun dismiss_putsTheProgressBackToIdle() = runTest {
        val f = newFixture()

        f.coordinator.start(BOARD_ID)
        runAll()
        f.coordinator.dismiss()
        runAll()

        assertEquals(FirmwareUpdateProgress.Idle, f.coordinator.progress.value)
        assertNull(f.coordinator.deviceId.value)
    }

    @Test
    fun status_duringAnUpdate_isUpdateInProgress() = runTest {
        val f = newFixture()
        f.board.connectNow()

        f.coordinator.start(BOARD_ID)
        f.coordinator.progress.first { it is FirmwareUpdateProgress.Uploading }

        assertEquals(FirmwareUnavailableReason.UPDATE_IN_PROGRESS, f.status().reason)
    }

    private companion object {
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val OLD_HASH = "1111111111111111111111111111111111111111111111111111111111111111"
        const val NEW_HASH = "2222222222222222222222222222222222222222222222222222222222222222"
        const val CONNECT_DELAY_MILLIS = 1_000L
        const val UPLOAD_DELAY_MILLIS = 2_000L
        const val SWAP_MILLIS = 20_000L

        /** Longer than the swap time and the verify limit together. */
        const val FLOW_MILLIS = 600_000L
    }
}
