package com.snapshotpb.configuration.firmware

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.BoardUpdateService
import com.snapshotpb.configuration.bluetooth.FirmwareUploadState
import com.snapshotpb.configuration.bluetooth.UpdateServiceStatus
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator.Companion.EXPECTED_SWAP_MILLIS
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator.Companion.UNKNOWN_BOARD_VERSION_DETAIL
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator.Companion.UP_TO_DATE_DETAIL
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareImage
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The [FirmwareUpdateCoordinator] of the apps. It sends the Bluetooth requests of the update on
 * [bluetoothRequestManager] and reads their results from [bluetoothStateManager], so it works with
 * the engine of each platform and with the simulated board of a debug build.
 *
 * THREADING: each function gives its work to [scope], and every coroutine of the coordinator runs
 * on that scope. The default scope runs one coroutine at a time, so the state of the run needs no
 * lock. A test gives a scope on its test dispatcher.
 *
 * @param feed Gives the feed for each check: the feed of the firmware site, or the simulated feed of
 *   a debug build (see [FirmwareFeedOverride]).
 * @param policy The release gate and the site of the build.
 * @param deviceStateSource The advertisements of the boards, for the board version.
 */
class FirmwareUpdateCoordinatorImpl(
    private val feed: () -> FirmwareFeed,
    private val policy: FirmwareUpdatePolicy,
    private val bluetoothRequestManager: BluetoothRequestManager,
    private val bluetoothStateManager: BluetoothStateManager,
    private val deviceStateSource: BluetoothDeviceStateSource,
    private val scope: CoroutineScope = defaultScope(),
) : FirmwareUpdateCoordinator {

    private val _progress = MutableStateFlow<FirmwareUpdateProgress>(FirmwareUpdateProgress.Idle)
    override val progress: StateFlow<FirmwareUpdateProgress> = _progress.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    override val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    /** The releases that the coordinator read, by board name and channel. */
    private val releases = MutableStateFlow<Map<ReleaseKey, ReleaseLookup>>(emptyMap())

    /** The update of [progress]. Read and written only on [scope]. */
    private var currentRun: Run? = null

    /** The coroutine of [run] while it runs. Read and written only on [scope]. */
    private var job: Job? = null

    /** The request id of the next firmware request. Read and written only on [scope]. */
    private var nextRequestId = 1L

    override fun status(deviceId: String, channel: FirmwareChannel): Flow<FirmwareStatus> {
        val board = combine(
            bluetoothStateManager.connectedDevices,
            bluetoothStateManager.updateServices,
            bluetoothStateManager.boardIds,
            deviceStateSource.deviceState(),
        ) { connected, services, boardIds, devices ->
            val service = services[deviceId]
            BoardSnapshot(
                connected = deviceId in connected,
                service = service,
                boardVersion = boardVersionOf(deviceId, service, boardIds, devices),
            )
        }
        return combine(board, releases, _progress, _deviceId) { snapshot, lookups, progress, updating ->
            val key = snapshot.service?.target?.let { ReleaseKey(feed().siteUrl, it.board, channel) }
            StatusInput(deviceId, snapshot, key, key?.let(lookups::get), progress, updating)
        }
            .onEach { input -> if (input.key != null && input.lookup == null) loadRelease(input.key) }
            .map(::statusOf)
            .distinctUntilChanged()
    }

    override fun refresh() {
        scope.launch { releases.update { lookups -> lookups.filterValues { it is ReleaseLookup.Loading } } }
    }

    override fun start(deviceId: String, channel: FirmwareChannel) {
        scope.launch {
            if (job?.isCompleted == false) {
                log.d { "An update runs; the start for $deviceId does nothing" }
                return@launch
            }
            if (!policy.updatesEnabled) {
                log.d { "The firmware update is not released in this build; the start does nothing" }
                return@launch
            }
            val newRun = Run(deviceId, channel)
            currentRun = newRun
            _deviceId.value = deviceId
            job = scope.launch { execute(newRun) }
        }
    }

    override fun retry() {
        scope.launch {
            val current = currentRun ?: return@launch
            val failed = _progress.value as? FirmwareUpdateProgress.Failed ?: return@launch
            if (!failed.reason.canRetry || job?.isCompleted == false) return@launch
            // After the reset, the board has the image. Only an old image after the reset needs a
            // new upload.
            val verifyOnly = current.uploadedHash != null && failed.reason !in NEW_UPLOAD_FAILURES
            log.d { "Retry of the update of ${current.deviceId} after ${failed.reason}; verify only: $verifyOnly" }
            job = scope.launch {
                if (verifyOnly) {
                    verify(current)
                } else {
                    current.uploadedHash = null
                    execute(current)
                }
            }
        }
    }

    override fun cancel() {
        scope.launch {
            val current = currentRun ?: return@launch
            val running = job ?: return@launch
            if (!running.isActive) return@launch
            running.cancelAndJoin()
            // A start or a dismiss can come while the join waits. Then this cancel has nothing to do.
            if (currentRun !== current) return@launch
            log.d { "The user stopped the update of ${current.deviceId} at ${current.phase}" }
            when (current.phase) {
                // A lost link stops the upload on the board and releases its fire-control hold.
                Phase.UPLOAD -> send(BluetoothRequest.Disconnect(current.deviceId))
                Phase.SWAP, Phase.VERIFY -> send(BluetoothRequest.StopScan)
                Phase.CHECK, Phase.DOWNLOAD, Phase.PREPARE -> Unit
            }
            _progress.value = FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.CANCELLED)
        }
    }

    override fun dismiss() {
        scope.launch {
            if (job?.isCompleted == false) return@launch
            currentRun = null
            _deviceId.value = null
            _progress.value = FirmwareUpdateProgress.Idle
        }
    }

    /** Steps 1 to 8 of the update. */
    private suspend fun execute(run: Run) {
        run.phase = Phase.CHECK
        _progress.value = FirmwareUpdateProgress.Checking
        val target = connectedTarget(run.deviceId) ?: return
        val snapshot = boardSnapshot(run.deviceId)
        run.advertisedBefore = snapshot.advertised
        val boardVersion = snapshot.version
        val release = readRelease(target, run.channel) ?: return
        when {
            !release.fits(target) -> return fail(FirmwareUpdateFailure.INCOMPATIBLE_HARDWARE)
            boardVersion == null -> return fail(FirmwareUpdateFailure.NO_RELEASE, UNKNOWN_BOARD_VERSION_DETAIL)
            !release.isNewerThan(boardVersion) -> return fail(FirmwareUpdateFailure.NO_RELEASE, UP_TO_DATE_DETAIL)
        }
        run.release = release

        run.phase = Phase.DOWNLOAD
        val image = download(release) ?: return

        run.phase = Phase.PREPARE
        _progress.value = FirmwareUpdateProgress.Preparing
        connectedTarget(run.deviceId) ?: return

        run.phase = Phase.UPLOAD
        val uploaded = upload(run.deviceId, image) ?: return
        run.uploadedHash = uploaded

        awaitSwap(run)
        verify(run)
    }

    /**
     * The hardware target of the connected board [deviceId]. Fails the update and gives `null` when
     * the board is not connected or has no update service.
     */
    private fun connectedTarget(deviceId: String): HardwareTarget? {
        if (deviceId !in bluetoothStateManager.connectedDevices.value) {
            fail(FirmwareUpdateFailure.NOT_CONNECTED)
            return null
        }
        val service = bluetoothStateManager.updateServices.value[deviceId]
        val target = service?.target?.takeIf { service.status == UpdateServiceStatus.AVAILABLE }
        if (target == null) fail(FirmwareUpdateFailure.NO_UPDATE_SERVICE, service?.status?.name)
        return target
    }

    /** Reads the newest release of [target] again, and keeps it for [status]. */
    private suspend fun readRelease(target: HardwareTarget, channel: FirmwareChannel): FirmwareManifest? {
        val siteFeed = feed()
        val key = ReleaseKey(siteFeed.siteUrl, target.board, channel)
        val release = try {
            siteFeed.latest(target.board, channel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.w(error) { "The check of the firmware site failed" }
            fail(FirmwareUpdateFailure.NETWORK, error::class.simpleName)
            return null
        }
        releases.update { lookups -> lookups + (key to ReleaseLookup.Found(release)) }
        if (release == null) fail(FirmwareUpdateFailure.NO_RELEASE)
        return release
    }

    private suspend fun download(release: FirmwareManifest): FirmwareImage? {
        _progress.value = FirmwareUpdateProgress.Downloading(0L, release.assets.image.size)
        return try {
            feed().download(release) { received, total ->
                _progress.value = FirmwareUpdateProgress.Downloading(received, total)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirmwareFeedException) {
            log.w(error) { "The download of ${release.version} failed" }
            val reason = when (error) {
                is FirmwareFeedException.BadHash,
                is FirmwareFeedException.BadSize,
                is FirmwareFeedException.BadJson -> FirmwareUpdateFailure.BAD_IMAGE
                is FirmwareFeedException.Http,
                is FirmwareFeedException.UnsupportedSchema -> FirmwareUpdateFailure.NETWORK
            }
            fail(reason, error::class.simpleName)
            null
        } catch (error: Exception) {
            log.w(error) { "The download of ${release.version} failed" }
            fail(FirmwareUpdateFailure.NETWORK, error::class.simpleName)
            null
        }
    }

    /** Sends [image] to the board. Gives the MCUboot hash of the uploaded image, or `null` after a failure. */
    private suspend fun upload(deviceId: String, image: FirmwareImage): String? {
        val requestId = nextRequestId++
        if (!send(BluetoothRequest.UpdateFirmware(deviceId, image, requestId))) {
            fail(FirmwareUpdateFailure.NOT_CONNECTED, NO_ANSWER_DETAIL)
            return null
        }
        val end = awaitEngine(requestId) { state ->
            val step = state.progress
            if (step is FirmwareUpdateProgress.Uploading || step is FirmwareUpdateProgress.Resetting) {
                _progress.value = step
            }
        }
        if (end == null) {
            // The request can still be on its way to the engine. A disconnect stops an upload that
            // starts later, so the board does not reset after the update failed.
            send(BluetoothRequest.Disconnect(deviceId))
            return null
        }
        return when (val step = end.progress) {
            is FirmwareUpdateProgress.AwaitingSwap -> {
                if (end.uploadedImageHash == null) fail(FirmwareUpdateFailure.BOARD_REFUSED, "NoImageHash")
                end.uploadedImageHash
            }
            is FirmwareUpdateProgress.Failed -> {
                _progress.value = step
                null
            }
            else -> {
                fail(FirmwareUpdateFailure.BOARD_REFUSED, step::class.simpleName)
                null
            }
        }
    }

    /**
     * Waits for the end of the engine request [requestId], and gives each state of it to [onState].
     * Fails the update and gives `null` when the engine gives no answer in [ENGINE_ANSWER_LIMIT_MILLIS].
     */
    private suspend fun awaitEngine(requestId: Long, onState: (FirmwareUploadState) -> Unit): FirmwareUploadState? {
        val answered = withTimeoutOrNull(ENGINE_ANSWER_LIMIT_MILLIS) {
            bluetoothStateManager.firmwareUpload.first { it.requestId == requestId }
        }
        if (answered == null) {
            log.w { "No answer to the firmware request $requestId" }
            fail(FirmwareUpdateFailure.NOT_CONNECTED, NO_ANSWER_DETAIL)
            return null
        }
        return bluetoothStateManager.firmwareUpload
            .onEach { state -> if (state.requestId == requestId) onState(state) }
            .first { state -> state.requestId == requestId && state.progress.endsARequest() }
    }

    /**
     * Step 6: waits [EXPECTED_SWAP_MILLIS] for the swap, with a scan for the board. The wait ends
     * sooner when the advertisement of the board shows a version that is not the version from
     * before the update. Only Android gives the version in the advertisement.
     */
    private suspend fun awaitSwap(run: Run) {
        run.phase = Phase.SWAP
        run.staleLink = true
        _progress.value = FirmwareUpdateProgress.AwaitingSwap(0L, EXPECTED_SWAP_MILLIS)
        send(BluetoothRequest.StartScan)
        withTimeoutOrNull(EXPECTED_SWAP_MILLIS) {
            coroutineScope {
                val ticker = launch {
                    var elapsed = 0L
                    while (true) {
                        delay(SWAP_TICK_MILLIS)
                        elapsed += SWAP_TICK_MILLIS
                        _progress.value = FirmwareUpdateProgress.AwaitingSwap(elapsed, EXPECTED_SWAP_MILLIS)
                    }
                }
                combine(deviceStateSource.deviceState(), bluetoothStateManager.boardIds) { devices, boardIds ->
                    advertisedVersion(run.deviceId, devices, boardIds)
                }.first { version -> version != null && version != run.advertisedBefore }
                log.d { "The board ${run.deviceId} advertises a new version before the end of the swap time" }
                ticker.cancel()
            }
        }
    }

    /**
     * Step 7: connects to the board, checks that it runs the uploaded image, and confirms it.
     *
     * The board runs the new image when the hash of its active image (`image list`) is the uploaded
     * hash. A board that runs another image (the old one) did not keep the update: the bootloader of
     * the update firmware uses swap using move, so the result is [FirmwareUpdateFailure.REVERTED].
     * The app cannot tell this from an overwrite-only bootloader that did not copy the image, so it
     * does not report [FirmwareUpdateFailure.NOT_COPIED]. The app reports [FirmwareUpdateProgress.Done]
     * only after it read the image list, so no unconfirmed image shows as done.
     */
    private suspend fun verify(run: Run) {
        run.phase = Phase.VERIFY
        _progress.value = FirmwareUpdateProgress.Verifying
        // The scan of the swap step ends here. A connect finds the board by its scan key, and the
        // engine scans by itself when it does not know the key.
        send(BluetoothRequest.StopScan)
        if (run.staleLink) dropStaleLink(run.deviceId)
        run.staleLink = false
        val service = when (val link = connectForVerify(run.deviceId)) {
            VerifyLink.NotFound -> return fail(FirmwareUpdateFailure.NO_ADVERTISEMENT)
            VerifyLink.NoImageList -> return fail(FirmwareUpdateFailure.BOARD_REFUSED, NO_IMAGE_LIST_DETAIL)
            is VerifyLink.Ready -> link.service
        }
        val active = service.activeImage
        if (service.status != UpdateServiceStatus.AVAILABLE || active == null) {
            // The new image has no update service, so the app can neither check nor confirm it.
            return fail(FirmwareUpdateFailure.NO_UPDATE_SERVICE, service.status.name)
        }
        if (!active.hash.equals(run.uploadedHash, ignoreCase = true)) {
            log.w { "The board ${run.deviceId} runs ${active.version}, not the uploaded image" }
            return fail(FirmwareUpdateFailure.REVERTED)
        }
        if (!active.confirmed && !confirm(run.deviceId)) return
        feed().clearDownload()
        _progress.value = FirmwareUpdateProgress.Done(run.release?.firmwareVersion)
    }

    /**
     * Disconnects a link from before the reset. A slow link-loss check (Linux) can keep the board in
     * the connected set after the reset, with the update service of the old image.
     */
    private suspend fun dropStaleLink(deviceId: String) {
        if (deviceId !in bluetoothStateManager.connectedDevices.value) return
        log.d { "The board $deviceId is still in the connected set after the reset; disconnect it" }
        send(BluetoothRequest.Disconnect(deviceId))
        withTimeoutOrNull(STALE_LINK_LIMIT_MILLIS) {
            bluetoothStateManager.connectedDevices.first { deviceId !in it }
        }
    }

    /** Sends the confirm request. Gives false after a failure. */
    private suspend fun confirm(deviceId: String): Boolean {
        val requestId = nextRequestId++
        if (!send(BluetoothRequest.ConfirmFirmware(deviceId, requestId))) {
            fail(FirmwareUpdateFailure.NOT_CONNECTED, NO_ANSWER_DETAIL)
            return false
        }
        val end = awaitEngine(requestId) {} ?: return false
        val step = end.progress
        if (step is FirmwareUpdateProgress.Failed) {
            _progress.value = step
            return false
        }
        return true
    }

    /**
     * Connects to the board until its update service is read with an image list, or until
     * [VERIFY_LIMIT_MILLIS]. The board advertises only after the user turns its radio on, so each
     * failed connect waits [VERIFY_RETRY_DELAY_MILLIS] and tries again. A connected board whose read
     * failed gets a new connect too: the engine reads its update service again. The limit stops the
     * attempts, and the user can then [retry].
     */
    private suspend fun connectForVerify(deviceId: String): VerifyLink {
        var ready: BoardUpdateService? = null
        var connectedOnce = false
        withTimeoutOrNull(VERIFY_LIMIT_MILLIS) {
            while (ready == null) {
                ready = readyService(deviceId)
                if (ready == null && send(BluetoothRequest.Connect(deviceId))) {
                    awaitConnectAttempt(deviceId)
                    if (deviceId in bluetoothStateManager.connectedDevices.value) connectedOnce = true
                    ready = readyService(deviceId)
                }
                if (ready == null) delay(VERIFY_RETRY_DELAY_MILLIS)
            }
        }
        return ready?.let(VerifyLink::Ready) ?: if (connectedOnce) VerifyLink.NoImageList else VerifyLink.NotFound
    }

    /**
     * Waits for the end of one connect attempt: the board is connected and its update service read
     * ended, or the attempt ended with no connection. [connectForVerify] gives the time limit.
     */
    private suspend fun awaitConnectAttempt(deviceId: String) {
        // The engine runs the attempt on its own scope. Wait for it to start, so the state from
        // before the request does not end the wait. A fast attempt can go past Connecting too soon
        // to see it, and then the state below shows its end.
        withTimeoutOrNull(CONNECT_START_LIMIT_MILLIS) {
            bluetoothStateManager.bluetoothState.first { it is BluetoothState.Connecting }
        }
        combine(
            bluetoothStateManager.bluetoothState,
            bluetoothStateManager.connectedDevices,
            bluetoothStateManager.updateServices,
        ) { state, connected, services ->
            val status = services[deviceId]?.status
            if (deviceId in connected) status != null && status != UpdateServiceStatus.CHECKING
            else state !is BluetoothState.Connecting
        }.first { ended -> ended }
    }

    /**
     * The update service of the connected board [deviceId] when the verify step can use it: an
     * image list was read, or the board has no update service that a new read could change.
     */
    private fun readyService(deviceId: String): BoardUpdateService? {
        if (deviceId !in bluetoothStateManager.connectedDevices.value) return null
        val service = bluetoothStateManager.updateServices.value[deviceId] ?: return null
        return service.takeIf {
            when (it.status) {
                UpdateServiceStatus.AVAILABLE -> it.activeImage != null
                UpdateServiceStatus.ABSENT, UpdateServiceStatus.UNSUPPORTED_PLATFORM -> true
                UpdateServiceStatus.CHECKING, UpdateServiceStatus.FAILED -> false
            }
        }
    }

    /**
     * Sends [request]. The bus has no buffer, so a request with no collector is lost: this waits up
     * to [REQUEST_SEND_LIMIT_MILLIS] for a collector (an Android activity that the system makes
     * again), and gives false when none comes.
     */
    private suspend fun send(request: BluetoothRequest): Boolean =
        (
            withTimeoutOrNull(REQUEST_SEND_LIMIT_MILLIS) {
                bluetoothRequestManager.subscriptionCount.first { it > 0 }
                bluetoothRequestManager.send(request)
            } != null
            ).also { sent -> if (!sent) log.w { "No collector took the request $request" } }

    private fun fail(reason: FirmwareUpdateFailure, detail: String? = null) {
        log.d { "The firmware update stops: $reason ${detail.orEmpty()}" }
        _progress.value = FirmwareUpdateProgress.Failed(reason, detail)
    }

    /** Starts the read of the release of [key] when no read of it ran. */
    private fun loadRelease(key: ReleaseKey) {
        scope.launch {
            if (key in releases.value) return@launch
            releases.update { lookups -> lookups + (key to ReleaseLookup.Loading) }
            val lookup = try {
                ReleaseLookup.Found(feed().latest(key.board, key.channel))
            } catch (cancelled: CancellationException) {
                releases.update { lookups -> lookups - key }
                throw cancelled
            } catch (error: Exception) {
                log.w(error) { "The check of the firmware site for ${key.board} failed" }
                ReleaseLookup.Failed
            }
            releases.update { lookups -> lookups + (key to lookup) }
        }
    }

    private suspend fun boardSnapshot(deviceId: String): VersionSnapshot {
        val devices = deviceStateSource.deviceState().first()
        val boardIds = bluetoothStateManager.boardIds.value
        val service = bluetoothStateManager.updateServices.value[deviceId]
        return VersionSnapshot(
            advertised = advertisedVersion(deviceId, devices, boardIds),
            version = boardVersionOf(deviceId, service, boardIds, devices),
        )
    }

    private fun statusOf(input: StatusInput): FirmwareStatus {
        val service = input.board.service
        val target = service?.target
        val lookup = input.lookup
        val latest = (lookup as? ReleaseLookup.Found)?.release
        val boardVersion = input.board.boardVersion
        val reason = when {
            input.updating != null && input.progress.isRunning() -> FirmwareUnavailableReason.UPDATE_IN_PROGRESS
            !input.board.connected -> FirmwareUnavailableReason.NOT_CONNECTED
            service == null || service.status == UpdateServiceStatus.CHECKING -> FirmwareUnavailableReason.CHECKING
            service.status == UpdateServiceStatus.ABSENT -> FirmwareUnavailableReason.NO_UPDATE_SERVICE
            service.status == UpdateServiceStatus.UNSUPPORTED_PLATFORM -> FirmwareUnavailableReason.PLATFORM_UNSUPPORTED
            service.status == UpdateServiceStatus.FAILED || target == null -> FirmwareUnavailableReason.UPDATE_SERVICE_ERROR
            lookup == null || lookup == ReleaseLookup.Loading -> FirmwareUnavailableReason.CHECKING
            lookup == ReleaseLookup.Failed -> FirmwareUnavailableReason.CHECK_FAILED
            latest == null -> FirmwareUnavailableReason.NO_RELEASE
            !latest.fits(target) -> FirmwareUnavailableReason.INCOMPATIBLE_HARDWARE
            boardVersion == null -> FirmwareUnavailableReason.UNKNOWN_BOARD_VERSION
            !latest.isNewerThan(boardVersion) -> FirmwareUnavailableReason.UP_TO_DATE
            !policy.updatesEnabled -> FirmwareUnavailableReason.NOT_YET_RELEASED
            else -> null
        }
        return FirmwareStatus(
            deviceId = input.deviceId,
            connected = input.board.connected,
            boardVersion = boardVersion,
            target = target,
            latest = latest,
            reason = reason,
        )
    }

    /** The steps of a run, so [cancel] and [retry] know where the run stopped. */
    private enum class Phase { CHECK, DOWNLOAD, PREPARE, UPLOAD, SWAP, VERIFY }

    /** One update. Read and written only on [scope]. */
    private class Run(val deviceId: String, val channel: FirmwareChannel) {
        var phase: Phase = Phase.CHECK
        var release: FirmwareManifest? = null
        var advertisedBefore: FirmwareVersion? = null

        /** True from the reset to the first verify: a connected entry is then from the old image. */
        var staleLink: Boolean = false

        /** The MCUboot hash of the uploaded image. It is set when the board got the reset command. */
        var uploadedHash: String? = null
    }

    /** A release of one site: the simulated site of a debug build and the real site do not share releases. */
    private data class ReleaseKey(val siteUrl: String, val board: String, val channel: FirmwareChannel)

    /** The result of the connect of the verify step. */
    private sealed interface VerifyLink {
        class Ready(val service: BoardUpdateService) : VerifyLink
        data object NoImageList : VerifyLink
        data object NotFound : VerifyLink
    }

    private sealed interface ReleaseLookup {
        data object Loading : ReleaseLookup
        data class Found(val release: FirmwareManifest?) : ReleaseLookup
        data object Failed : ReleaseLookup
    }

    private data class BoardSnapshot(
        val connected: Boolean,
        val service: BoardUpdateService?,
        val boardVersion: FirmwareVersion?,
    )

    private data class StatusInput(
        val deviceId: String,
        val board: BoardSnapshot,
        val key: ReleaseKey?,
        val lookup: ReleaseLookup?,
        val progress: FirmwareUpdateProgress,
        val updating: String?,
    )

    private class VersionSnapshot(val advertised: FirmwareVersion?, val version: FirmwareVersion?)

    companion object {
        /** The interval of the elapsed time in [FirmwareUpdateProgress.AwaitingSwap]. */
        private const val SWAP_TICK_MILLIS = 1_000L

        /**
         * The time limit of the verify step. It starts after [EXPECTED_SWAP_MILLIS], and it gives the
         * user time to turn the radio on in the board menu.
         */
        const val VERIFY_LIMIT_MILLIS: Long = 120_000L

        /** The wait after a failed connect of the verify step. */
        private const val VERIFY_RETRY_DELAY_MILLIS = 3_000L

        /** The time for the engine to start a connect attempt. */
        private const val CONNECT_START_LIMIT_MILLIS = 3_000L

        /** The time for a disconnect of a link from before the reset. */
        private const val STALE_LINK_LIMIT_MILLIS = 5_000L

        /** The failures after the reset that need a new upload: the board runs the old image. */
        private val NEW_UPLOAD_FAILURES = setOf(FirmwareUpdateFailure.REVERTED, FirmwareUpdateFailure.NOT_COPIED)

        /** The detail of a verify step that read no image list. */
        private const val NO_IMAGE_LIST_DETAIL = "NoImageList"

        /** The time for the engine to give the first state of a firmware request. */
        private const val ENGINE_ANSWER_LIMIT_MILLIS = 30_000L

        /** The time for the request bus to take a request. */
        private const val REQUEST_SEND_LIMIT_MILLIS = 10_000L

        /** The detail of a failure when the engine does not answer. */
        private const val NO_ANSWER_DETAIL = "NoAnswer"

        private val log = Arbor.tag("FirmwareUpdateCoordinator")

        /**
         * The default scope: one coroutine at a time on the default dispatcher. A failed coroutine
         * does not stop the others, and the handler logs the failure.
         */
        fun defaultScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, error ->
                log.e(error) { "Unhandled failure in the firmware update" }
            }
        )

        private fun FirmwareUpdateProgress.isRunning(): Boolean = when (this) {
            FirmwareUpdateProgress.Idle, is FirmwareUpdateProgress.Done, is FirmwareUpdateProgress.Failed -> false
            else -> true
        }

        private fun FirmwareUpdateProgress.endsARequest(): Boolean =
            this is FirmwareUpdateProgress.AwaitingSwap ||
                this is FirmwareUpdateProgress.Done ||
                this is FirmwareUpdateProgress.Failed
    }
}

/**
 * The firmware version of the board [deviceId] in the advertisement of one of its scan keys, or
 * `null` when no advertisement has one. Only Android gives the advertisement data.
 */
internal fun advertisedVersion(
    deviceId: String,
    devices: BluetoothDeviceState,
    boardIds: Map<String, String>,
): FirmwareVersion? =
    devices.advertisementData.entries
        .filter { (scanKey, _) -> scanKey == deviceId || boardIds[scanKey] == deviceId }
        .firstNotNullOfOrNull { (_, data) -> data.firmwareVersion }

/**
 * The firmware version of the board [deviceId]. The advertisement has the dev flag, so it wins
 * when it has the numbers of the running image. The running image wins when the two do not agree,
 * because an advertisement in the scan results can be from before the last reset.
 */
internal fun boardVersionOf(
    deviceId: String,
    service: BoardUpdateService?,
    boardIds: Map<String, String>,
    devices: BluetoothDeviceState,
): FirmwareVersion? {
    val advertised = advertisedVersion(deviceId, devices, boardIds)
    val running = service?.activeImage?.firmwareVersion ?: return advertised
    return advertised?.takeIf { it.year == running.year && it.week == running.week && it.count == running.count }
        ?: running
}
