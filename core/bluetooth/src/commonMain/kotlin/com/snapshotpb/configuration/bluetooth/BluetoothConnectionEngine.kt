package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsClient
import com.snapshotpb.configuration.bluetooth.ots.OtsObject
import com.snapshotpb.configuration.bluetooth.ots.ProfilesWire
import com.snapshotpb.configuration.bluetooth.ots.activeProfileSlot
import com.snapshotpb.configuration.bluetooth.ots.bootTextToDomain
import com.snapshotpb.configuration.bluetooth.ots.getCurrentLocalDate
import com.snapshotpb.configuration.bluetooth.ots.readDeviceConfigObject
import com.snapshotpb.configuration.bluetooth.ots.readDeviceConfigObjectAfterCommit
import com.snapshotpb.configuration.bluetooth.ots.readDeviceObject
import com.snapshotpb.configuration.bluetooth.ots.readDeviceProfiles
import com.snapshotpb.configuration.bluetooth.ots.screenOffDelayToDomain
import com.snapshotpb.configuration.bluetooth.ots.toDomain
import com.snapshotpb.configuration.bluetooth.ots.toDomainDevice
import com.snapshotpb.configuration.bluetooth.ots.toGenerated
import com.snapshotpb.configuration.bluetooth.ots.toProtocolDeviceConfig
import com.snapshotpb.configuration.bluetooth.ots.toProtocolProfilesWire
import com.snapshotpb.configuration.bluetooth.ots.withModesAsWritten
import com.snapshotpb.configuration.bluetooth.ots.writeDeviceConfiguration
import com.snapshotpb.configuration.bluetooth.smp.BlueFalconSmpTransport
import com.snapshotpb.configuration.bluetooth.smp.SmpOpenResult
import com.snapshotpb.configuration.bluetooth.smp.SmpWriteUnsupported
import com.snapshotpb.configuration.bluetooth.smp.hasSmpService
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.FirmwareImage
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.withProfilesInWrittenSlots
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.ConnectionPriority
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import snapshotpb.v1.DeviceConfig

/**
 * Cross-platform core of the Bluetooth stack: the connect / teardown / link-loss-monitor / OTS
 * read+write pipeline that was previously copy-pasted into the four per-platform `BluetoothManager`s.
 * Everything platform-specific is delegated to a [PlatformBluetoothSeams] passed in at construction
 * (composition); a platform `BluetoothManager` owns one engine and forwards its public API to it.
 *
 * Because [seams] arrives fully constructed, this engine launches its two BlueFalcon collectors from
 * its own `init` safely — the collectors call into a ready [seams], so there is no leaked-`this` and
 * no init-ordering contract for callers to honor.
 *
 * IDENTITY (#127): the board `device_id` is the [Device.id], the same on every phone and platform.
 * The scan key of the peripheral (see [PlatformBluetoothSeams.deviceState]) is only the key of the
 * session: the maps below use it, and nothing persists it. The public calls accept either id, and
 * [BluetoothStateManager.connectedDevices] and each read result carry the board id.
 */
class BluetoothConnectionEngine(
    private val blueFalcon: BlueFalcon,
    private val bluetoothStateManager: BluetoothStateManager,
    private val bleDebugLog: BleDebugLog,
    private val deviceReadResultBus: DeviceReadResultBus,
    private val deviceWriteResultBus: DeviceWriteResultBus,
    private val seams: PlatformBluetoothSeams,
    private val coroutineScope: CoroutineScope = defaultScope()
) {
    /** Discovered-device state, owned by [seams]; exposed for the platform manager / UI to observe. */
    val deviceState: StateFlow<BluetoothDeviceState> get() = seams.deviceState

    private val debugJobs: MutableMap<String, List<Job>> = mutableMapOf()
    private val otsClients: MutableMap<String, OtsClient> = mutableMapOf()
    /**
     * Ids already logged on first discovery, so a busy scan doesn't repeat the same line. Bounded:
     * it only ever grows, and in a crowded RF environment an unbounded set of every peripheral seen
     * for the process lifetime is a slow leak for a purely cosmetic "log this once" marker.
     */
    private val loggedPeripheralIds: MutableSet<String> = mutableSetOf()

    /**
     * The board's last-read `Profiles` per device, captured on connect and replaced after each
     * successful write. Lets a later write preserve board-owned per-profile fields the domain model
     * doesn't carry (finding #7), and keep an enum number this app does not know (M2). Guarded by
     * [connectionLock] like [otsClients].
     *
     * An entry exists only after the initial read succeeded, so its presence is also the gate that
     * lets a write start (H1): a write without it would reset `board_auto_off` in every slot.
     */
    private val lastReadProfiles: MutableMap<String, ProfilesWire> = mutableMapOf()

    /**
     * The board id that [BluetoothStateManager.deviceConnected] got for each connected scan key. An
     * entry exists only while the link is up and its read succeeded, so it is also the connected
     * flag of the session. Guarded by [connectionLock].
     */
    private val connectedBoardIds: MutableMap<String, String> = mutableMapOf()

    /**
     * The board's last-read `DeviceConfig` per device, set with [lastReadProfiles]. A write compares
     * the board with these two copies first, so an edit made in the board menu is not overwritten
     * (see [showsABoardEdit]). Guarded by [connectionLock].
     */
    private val lastReadDeviceConfig: MutableMap<String, DeviceConfig> = mutableMapOf()

    /**
     * The SMP session of each connected scan key whose board has the update service (see
     * [readUpdateService]). Guarded by [connectionLock]. A firmware step uses a session only while
     * it holds [writeMutex]. A teardown closes the session also while an upload uses it: that close
     * and the disconnect are how a Disconnect stops an upload.
     */
    private val smpSessions: MutableMap<String, BoardFirmwareSession> = mutableMapOf()

    /**
     * The board id of each scan key that has an entry in [BluetoothStateManager.updateServices], so
     * the teardown removes the entry of that board. Guarded by [connectionLock].
     */
    private val updateServiceBoardIds: MutableMap<String, String> = mutableMapOf()

    /**
     * Serializes board writes. A write runs on [coroutineScope], not on the caller, so a caller that
     * is cancelled (an Android activity recreation) cannot stop a write halfway (M1).
     *
     * A connect attempt holds it too, so a re-read on a live link cannot run between the steps of
     * a write. Lock order: [writeMutex] first, then [connectionLock]. Never take [writeMutex] while
     * [connectionLock] is held, or a write that waits for [connectionLock] deadlocks.
     */
    private val writeMutex = Mutex()

    /**
     * The single in-flight connect attempt, if any. Cancelled by a new connect or a disconnect.
     * Only mutated from [connect]/[disconnect], which the platform manager always invokes from its
     * single request-bus collector, so the plain `var` is visible across those calls without extra
     * synchronization (finding #47).
     */
    private var connectionJob: Job? = null

    /** Per-device link-loss monitors started after a successful connect. */
    private val connectionMonitors: MutableMap<String, Job> = mutableMapOf()

    /**
     * Serializes the connect/teardown/monitor critical sections so the shared maps above are never
     * mutated concurrently: connect attempts run on [coroutineScope]'s thread pool, disconnect runs
     * on the request-bus collector thread, and monitors fire on arbitrary poll ticks. Holding it
     * across a whole attempt also guarantees a cancelled attempt's cleanup completes — and its
     * finally observes un-clobbered state — before the next attempt begins.
     */
    private val connectionLock = Mutex()

    init {
        // Safe to launch here: [seams] is fully constructed (passed into the constructor), so the
        // collectors below call into a ready object. This is the property composition buys over the
        // old open-base design, where launching from the base constructor would invoke overridden
        // members before the subclass's fields were initialized (leaked-`this`).
        coroutineScope.launch {
            blueFalcon.peripherals.collect { peripherals ->
                // Engines emit peripherals on first discovery with the name still null and never
                // re-emit when it updates (StateFlow<Set<…>> dedupes by UUID-based equals), so
                // filtering by name here silently drops the board. Surface every peripheral and let
                // the UI handle filtering by name later.
                peripherals.forEach { peripheral ->
                    if (loggedPeripheralIds.size < MAX_LOGGED_PERIPHERAL_IDS &&
                        loggedPeripheralIds.add(peripheral.uuid)
                    ) {
                        log.d {
                            "Discovered peripheral uuid=${peripheral.uuid} name=${peripheral.name} rssi=${peripheral.rssi}"
                        }
                    }
                    seams.addDevice(peripheral)
                }
            }
        }
        coroutineScope.launch {
            blueFalcon.managerState.collect { state ->
                log.d { "BlueFalcon managerState=$state" }
                seams.onManagerState(state)
            }
        }
    }

    suspend fun startScan() {
        if (bluetoothStateManager.bluetoothState.value == BluetoothState.Ready) {
            log.d("Starting scan")
            seams.scanForDevices()
            bluetoothStateManager.update(BluetoothState.Scanning)
        }
    }

    /**
     * One scan on a platform where a scan is one user choice (the Web Bluetooth device list). It does
     * not set [BluetoothState.Scanning], because no scan runs after the choice. The chosen board
     * appears in [deviceState] like a scan result.
     */
    suspend fun scanOnce() {
        if (bluetoothStateManager.bluetoothState.value == BluetoothState.Ready) {
            log.d("Starting a one-choice scan")
            seams.scanForDevices()
        }
    }

    suspend fun stopScan() {
        log.d("Stopping scan")
        seams.stopScanForDevices()
        if (bluetoothStateManager.bluetoothState.value == BluetoothState.Scanning) {
            bluetoothStateManager.update(BluetoothState.Ready)
        }
    }

    /**
     * Starts a connect attempt for [deviceId] without blocking the caller (the request bus), so a
     * subsequent Disconnect/Cancel is serviced immediately instead of waiting out the connect.
     * Any previous attempt is cancelled first, and the new one waits for that cancellation to finish
     * cleaning up before it runs — guaranteeing only a single connect is ever in flight.
     */
    fun connect(deviceId: String) {
        connectionJob?.cancel()
        connectionJob = coroutineScope.launch { connectInternal(deviceId) }
    }

    private suspend fun connectInternal(deviceId: String) {
        // The lock both confines the shared-map mutations below and serializes attempts: a queued
        // attempt waits here until the previous one releases the lock in its finally, so a cancelled
        // attempt's teardown always completes first. A waiter cancelled before acquiring never ran,
        // so it has nothing to clean up.
        //
        // The write lock comes first. A connect to a live link reads the board again and records
        // its Profiles (N6). Without the write lock, that read ran while a write held no lock
        // between its steps: the read result came before the write result, and the write then
        // replaced the Profiles that the read recorded. A write refuses to start before the
        // initial read, so to wait for a write here costs nothing on a new link.
        writeMutex.withLock { connectionLock.withLock { connectAttempt(deviceId) } }
    }

    /**
     * [deviceId] is the id that the UI has: the board id of a known board, or the scan key of a
     * board that the scan found and whose board id the app does not know yet. The scan key of the link is
     * `sessionKey` after it resolves.
     */
    private suspend fun connectAttempt(deviceId: String) {
        var sessionKey: String? = null
        try {
            if (bluetoothStateManager.bluetoothState.value == BluetoothState.Scanning) {
                // A throw from the platform scan stop must not abort the connect (L18).
                runCatching { stopScan() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.d { "Stopping the scan before a connect to $deviceId failed: ${error.message}" }
                    }
            }
            bluetoothStateManager.update(BluetoothState.Connecting(deviceId))

            bluetoothStateManager.resetProgress()
            bluetoothStateManager.updateProgress(
                ConnectionProgress(step1 = StepStatus.IN_PROGRESS)
            )

            if (!seams.awaitReadyToConnect(deviceId)) return

            // A second connect to a board that is already connected reuses the live link and only
            // reads the board again (N6). A second blueFalcon.connect on a live link can make the
            // Android engine keep a second BluetoothGatt.
            val knownKey = sessionKeyFor(deviceId)
            val live = knownKey?.let(::liveConnection)
            if (knownKey != null && live != null) {
                sessionKey = knownKey
                val (client, livePeripheral) = live
                log.d { "Device $deviceId is already connected; reading it again on the live link" }
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(
                        step1 = StepStatus.COMPLETED,
                        step2 = StepStatus.COMPLETED,
                        step3 = StepStatus.IN_PROGRESS
                    )
                )
                withHighConnectionPriority(livePeripheral) {
                    readInitialState(knownKey, client, livePeripheral, expectedBoardId(deviceId, knownKey))
                }
                restoreIdleState()
                readUpdateService(knownKey, livePeripheral)
                return
            }

            // Single active board: drop any other connection before opening a new one.
            disconnectOthers(except = knownKey)

            val found = ensurePeripheral(deviceId)
            if (found == null) {
                log.d { "Cannot connect, device $deviceId not discovered before timeout" }
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(step1 = StepStatus.FAILED)
                )
                restoreIdleState()
                return
            }
            val (key, peripheral) = found
            sessionKey = key

            // This retry is a safety net for a rare connect failure. A frequent failure (HCI reason
            // 0x3e, GATT_ERROR(133) on the central) had its cause in the BlueFalcon Android engine:
            // connect() did not keep the connectGatt handle, so a slow connect stayed open and the
            // board, which accepts one connection, stopped the next connects. BlueFalcon 3.5.1 and
            // later close the handle. One attempt can still fail at link establishment, usually at a
            // weak RF margin. The board accepts a new connect immediately after a drop, so a bounded
            // retry hides that failure from the user. The wait of each attempt has a limit, so a
            // dropped link tries again soon and does not wait for the full discovery timeout. See
            // tools/ble-validation/README.md for the history and the measurement method.
            var servicesDiscovered = false
            for (attempt in 1..CONNECT_ATTEMPTS) {
                log.d { "Connecting to $deviceId (attempt $attempt/$CONNECT_ATTEMPTS)" }
                // The attempt number goes into the progress, so the connect screen can show a retry.
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(
                        step1 = StepStatus.COMPLETED,
                        step2 = StepStatus.IN_PROGRESS,
                        attempt = attempt,
                        maxAttempts = CONNECT_ATTEMPTS,
                    )
                )
                blueFalcon.connect(peripheral)
                val discovered = withTimeoutOrNull(CONNECT_ATTEMPT_TIMEOUT_MILLIS) {
                    seams.awaitServicesDiscovered(peripheral)
                } == true
                if (discovered) {
                    servicesDiscovered = true
                    break
                }
                log.d { "Connect attempt $attempt/$CONNECT_ATTEMPTS for $deviceId dropped before services" }
                // Tear down the half-open link before retrying. Rethrow cancellation (a new connect or
                // a Disconnect cancelled us) so the finally below runs its teardown instead of this
                // swallowing it — same rule the OTS/write paths follow.
                runCatching { blueFalcon.disconnect(peripheral) }
                    .onFailure { error -> if (error is CancellationException) throw error }
                if (attempt < CONNECT_ATTEMPTS) delay(CONNECT_RETRY_BACKOFF_MILLIS)
            }

            var otsReady = false
            if (servicesDiscovered) {
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(
                        step1 = StepStatus.COMPLETED,
                        step2 = StepStatus.COMPLETED,
                        step3 = StepStatus.IN_PROGRESS
                    )
                )
                seams.onServicesDiscovered(peripheral)
                // Only observe a live, OTS-ready link: a failed OTS init or initial read tears the
                // link down (finding #6, H1), and the services-not-discovered branch already
                // disconnected the peripheral, so observing either would leak collectors on a dead
                // link.
                otsReady = withHighConnectionPriority(peripheral) {
                    initializeOtsClient(key, peripheral, expectedBoardId(deviceId, key))
                }
                if (otsReady) {
                    observeForDebug(peripheral)
                }
            } else {
                log.d { "Services not discovered for $deviceId after $CONNECT_ATTEMPTS attempts" }
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(
                        step1 = StepStatus.COMPLETED,
                        step2 = StepStatus.FAILED
                    )
                )
            }
            restoreIdleState()
            if (otsReady) readUpdateService(key, peripheral)
        } finally {
            // Every normal path above leaves Connecting, so a lingering Connecting(deviceId) means
            // the attempt was cancelled mid-flight (a new connect, or a Disconnect/Cancel from the
            // card) or it threw. Release any half-open GATT link and reset so the card returns to
            // Disconnected. NonCancellable lets the suspend teardown run inside the cancelled job;
            // the deviceId guard avoids clobbering a connect that already advanced.
            val state = bluetoothStateManager.bluetoothState.value
            if (state is BluetoothState.Connecting && state.deviceId == deviceId) {
                withContext(NonCancellable) {
                    log.d { "Connect to $deviceId cancelled; cleaning up" }
                    runCatching { seams.stopScanForDevices() }
                    val key = sessionKey ?: sessionKeyFor(deviceId) ?: deviceId
                    teardownConnection(key, disconnectPeripheral = true)
                    restoreIdleState()
                    bluetoothStateManager.resetProgress()
                }
            }
        }
    }

    /**
     * Puts the idle state that the radio supports back after a connect attempt (L14). A failed
     * connect used to set [BluetoothState.Ready] whatever the radio state was, so with Bluetooth off
     * a later scan went to [BluetoothState.Scanning] with no radio. The seam then applies its own
     * gates (Android permission and location, the iOS permission denial).
     */
    private fun restoreIdleState() {
        val radio = blueFalcon.managerState.value
        bluetoothStateManager.update(
            if (radio == BluetoothManagerState.Ready) BluetoothState.Ready else BluetoothState.Disabled
        )
        seams.onManagerState(radio)
    }

    /**
     * The OTS client and the peripheral of the scan key [sessionKey] when the board is connected,
     * its initial read succeeded, and BlueFalcon still reports the link as connected; otherwise
     * `null`. Must be called while [connectionLock] is held.
     */
    private fun liveConnection(sessionKey: String): Pair<OtsClient, BluetoothPeripheral>? {
        if (sessionKey !in connectedBoardIds) return null
        if (lastReadProfiles[sessionKey] == null) return null
        val client = otsClients[sessionKey] ?: return null
        val peripheral = seams.deviceState.value.devices[sessionKey] ?: return null
        if (blueFalcon.connectionState(peripheral) != BluetoothPeripheralState.Connected) return null
        return client to peripheral
    }

    /**
     * The scan key of [id] without a scan: [id] itself when it is a scan key that the scan found,
     * else the scan key that [BluetoothStateManager.boardIds] gives for the board [id]. `null` when
     * neither is known.
     */
    private fun sessionKeyFor(id: String): String? {
        if (id in seams.deviceState.value.devices) return id
        return scanKeyOfBoard(bluetoothStateManager.boardIds.value, id)
    }

    /**
     * The board id that the read over [sessionKey] must give, or `null` when any board may answer.
     * The UI names a board by its scan key only before the first read, so any board may answer that
     * request. A board id names one board, and a different board must not take its place.
     */
    private fun expectedBoardId(deviceId: String, sessionKey: String): String? =
        deviceId.takeIf { it != sessionKey }

    /**
     * Explicit disconnect for [deviceId]. Cancels any in-flight connect (this doubles as the Cancel
     * affordance on the connection card — "stop connecting" == "disconnect"), tears down the GATT
     * link, and clears the stale connection progress.
     */
    suspend fun disconnect(deviceId: String) {
        log.d { "Disconnecting $deviceId" }
        connectionJob?.let { job ->
            connectionJob = null
            job.cancel()
            job.join()
        }
        connectionLock.withLock {
            teardownConnection(sessionKeyFor(deviceId) ?: deviceId, disconnectPeripheral = true)
        }
        bluetoothStateManager.resetProgress()
    }

    /**
     * Releases everything held for the scan key [deviceId]: stops the link-loss monitor, cancels
     * debug observers, closes the OTS client, and clears the connected flag. When
     * [disconnectPeripheral] is true it also asks BlueFalcon to drop the GATT link (explicit
     * disconnect); detected link-loss passes false because the radio is already gone. Must be called
     * while [connectionLock] is held. A second call does nothing more, so two callers at the same time are safe.
     */
    private suspend fun teardownConnection(deviceId: String, disconnectPeripheral: Boolean) {
        connectionMonitors.remove(deviceId)?.cancel()
        debugJobs.remove(deviceId)?.forEach(Job::cancel)
        lastReadProfiles.remove(deviceId)
        lastReadDeviceConfig.remove(deviceId)
        otsClients.remove(deviceId)?.runCatching { close() }
            ?.onFailure { error -> log.d { "Closing OTS client failed for $deviceId: ${error.message}" } }
        closeUpdateService(deviceId)
        if (disconnectPeripheral) {
            val peripheral = seams.deviceState.value.devices[deviceId]
            if (peripheral != null) {
                runCatching { blueFalcon.disconnect(peripheral) }
                    .onFailure { error -> log.d { "Disconnect failed for $deviceId: ${error.message}" } }
            } else {
                log.d { "Disconnect requested for $deviceId but no peripheral is cached" }
            }
        }
        markDisconnected(deviceId)
    }

    /** Clears the connected flag of the scan key [sessionKey]. Must be called while [connectionLock] is held. */
    private fun markDisconnected(sessionKey: String) {
        connectedBoardIds.remove(sessionKey)?.let(bluetoothStateManager::deviceDisconnected)
    }

    /** Single active board: disconnect every connected scan key except [except]. */
    private suspend fun disconnectOthers(except: String?) {
        connectedBoardIds.keys
            .filter { it != except }
            .forEach { teardownConnection(it, disconnectPeripheral = true) }
    }

    /**
     * BlueFalcon exposes no connection-state callback, only the snapshot getter
     * [BlueFalcon.connectionState]. Poll it after a successful connect and tear down when the link
     * drops, so a powered-off / out-of-range board doesn't leave a stale "Connected" state with an
     * enabled "Save to board" that would later throw from the closed OTS client.
     */
    private fun startConnectionMonitor(deviceId: String, peripheral: BluetoothPeripheral) {
        connectionMonitors.remove(deviceId)?.cancel()
        connectionMonitors[deviceId] = coroutineScope.launch {
            val self = coroutineContext[Job]
            while (true) {
                delay(CONNECTION_MONITOR_INTERVAL_MILLIS)
                val state = blueFalcon.connectionState(peripheral)
                if (state == BluetoothPeripheralState.Disconnected ||
                    state == BluetoothPeripheralState.Disconnecting
                ) {
                    log.d { "Link to $deviceId lost (state=$state); tearing down" }
                    connectionLock.withLock {
                        // Only tear down if we're still the active monitor — a reconnect may have
                        // replaced us. Drop self from the map first so teardownConnection's own
                        // monitor-cancel can't cancel this coroutine mid-teardown.
                        if (connectionMonitors[deviceId] === self) {
                            connectionMonitors.remove(deviceId)
                            teardownConnection(deviceId, disconnectPeripheral = false)
                        }
                    }
                    break
                }
            }
        }
    }

    /**
     * Finds the scan key and the peripheral for [deviceId]. When the scan of this session did not
     * find it yet (for example, a known board that the Profiles screen connects), this starts a scan
     * and waits. Returns null when it does not appear before [SCAN_FOR_CONNECT_TIMEOUT_MILLIS].
     *
     * A board id can have no scan key yet: no read in this session and no 0xFFE1 advertisement gave
     * it. When no scan key matches it for [UNREAD_BOARD_WAIT_MILLIS], the engine takes the board with
     * the strongest signal whose board id it does not know. The read then must give [deviceId], else the attempt fails (see
     * [expectedBoardId]) and a second attempt skips that board, because the read recorded its id.
     */
    private suspend fun ensurePeripheral(deviceId: String): Pair<String, BluetoothPeripheral>? {
        findPeripheral(deviceId, acceptUnreadBoard = false)?.let { return it }
        log.d { "Peripheral $deviceId not yet discovered; scanning to locate it" }
        runCatching { seams.scanForDevices() }
        val found = withTimeoutOrNull(SCAN_FOR_CONNECT_TIMEOUT_MILLIS) {
            var waited = 0L
            var match = findPeripheral(deviceId, acceptUnreadBoard = false)
            while (match == null) {
                delay(SCAN_FOR_CONNECT_POLL_INTERVAL_MILLIS)
                waited += SCAN_FOR_CONNECT_POLL_INTERVAL_MILLIS
                match = findPeripheral(deviceId, acceptUnreadBoard = waited >= UNREAD_BOARD_WAIT_MILLIS)
            }
            match
        }
        runCatching { seams.stopScanForDevices() }
        return found
    }

    /**
     * The scan key and the peripheral of [deviceId] in the current scan results (see
     * [sessionKeyFor]). With [acceptUnreadBoard], a board id with no scan key takes the board with
     * the strongest signal whose board id this session does not know. The closest board is most probably the
     * board of the user.
     */
    private fun findPeripheral(deviceId: String, acceptUnreadBoard: Boolean): Pair<String, BluetoothPeripheral>? {
        val devices = seams.deviceState.value.devices
        val boardIds = bluetoothStateManager.boardIds.value
        val key = sessionKeyFor(deviceId)?.takeIf { it in devices }
            ?: devices.entries
                .filter { (scanKey, peripheral) ->
                    acceptUnreadBoard && scanKey !in boardIds && SnapshotAdvertisement.isSnapshotBoard(peripheral)
                }
                .maxByOrNull { (_, peripheral) -> peripheral.rssi ?: Float.NEGATIVE_INFINITY }
                ?.key
        return key?.let { scanKey -> devices[scanKey]?.let { scanKey to it } }
    }

    /**
     * Lists every OTS object exposed by the connected device, returning an empty
     * list if no OTS client was attached (e.g. the peripheral isn't running OTS).
     */
    suspend fun listOtsObjects(deviceId: String): List<OtsObject> {
        // Public API forwarded through every platform BluetoothManager: snapshot the client under
        // connectionLock (the shared maps are guarded only by convention), then do the GATT walk off
        // the lock (finding #47). Do NOT call this from a context already holding the lock — the
        // non-reentrant Mutex would deadlock; use [listObjectsOrEmpty] on the client directly there.
        val client = connectionLock.withLock { otsClients[sessionKeyFor(deviceId) ?: deviceId] } ?: run {
            log.d { "listOtsObjects($deviceId): no OTS client" }
            return emptyList()
        }
        return client.listObjectsOrEmpty(deviceId)
    }

    /** Walks a client's OTS objects, logging and swallowing any failure. Touches no shared map/lock. */
    private suspend fun OtsClient.listObjectsOrEmpty(deviceId: String): List<OtsObject> =
        runCatching { listObjects() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log.d { "OTS listObjects failed for $deviceId: ${error.message}" }
            }
            .getOrDefault(emptyList())

    /**
     * Attaches an [OtsClient] to the freshly connected [peripheral] (scan key [deviceId]) and reads
     * the board's state. Returns true when OTS is up and the initial read succeeded (link kept,
     * monitor started). Returns false when the peripheral exposes no OTS service,
     * [OtsClient.prepare] fails, or the initial read fails — in each case the just-opened link is
     * torn down so it can't survive untracked (finding #6). Must be called while [connectionLock]
     * is held.
     */
    private suspend fun initializeOtsClient(
        deviceId: String,
        peripheral: BluetoothPeripheral,
        expectedBoardId: String?,
    ): Boolean {
        connectionMonitors.remove(deviceId)?.cancel()
        otsClients.remove(deviceId)?.runCatching { close() }
        closeUpdateService(deviceId)
        lastReadProfiles.remove(deviceId)
        lastReadDeviceConfig.remove(deviceId)
        markDisconnected(deviceId)
        val client = runCatching {
            OtsClient.discover(blueFalcon, peripheral) { characteristic ->
                seams.awaitSubscribed(peripheral, characteristic)
            }
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log.d { "OTS discover failed for $deviceId: ${error.message}" }
            }
            .getOrNull()
        if (client == null) {
            // No OTS service (discover returned null) or prepare() threw. Services are discovered and
            // the GATT link is fully open but unusable: mark step 3 failed and tear the link down so
            // it can't survive untracked — an orphaned link breaks single-active-board
            // (disconnectOthers only iterates connectedBoardIds) and its later drop would never be
            // detected, and its debug collectors would leak (finding #6). Safe to call
            // teardownConnection here: connectAttempt holds connectionLock across this call.
            bluetoothStateManager.updateProgress(
                ConnectionProgress(
                    step1 = StepStatus.COMPLETED,
                    step2 = StepStatus.COMPLETED,
                    step3 = StepStatus.FAILED
                )
            )
            teardownConnection(deviceId, disconnectPeripheral = true)
            return false
        }
        // Register the client now so a cancelled attempt's teardown closes it. The device is NOT
        // marked connected yet: Save enables on the connected flag, and a write during the initial
        // read would interleave OTS selections and, with no last-read Profiles, reset
        // board_auto_off in every slot (H1). readInitialState marks it connected on success.
        otsClients[deviceId] = client
        startConnectionMonitor(deviceId, peripheral)
        log.d { "Discovered OTS on $deviceId" }
        indexOtsObjects(deviceId, client)
        return readInitialState(deviceId, client, peripheral, expectedBoardId)
    }

    /**
     * Reads the board's `device`, `profiles` and `device_config` objects over [client] and publishes
     * the result. Only a successful read records the last-read Profiles, marks the device connected
     * and emits the read result, so a write cannot start before the board state is known (H1). A
     * failed read marks its step failed and tears the link down, so no half-synced link stays open.
     * Returns whether the read succeeded. Must be called while [writeMutex] and [connectionLock] are
     * held, so no write runs between the three reads.
     *
     * [deviceId] is the scan key of the link. The read records the board id of that scan key, and
     * a board that is not [expectedBoardId] fails the read (see [expectedBoardId]).
     */
    private suspend fun readInitialState(
        deviceId: String,
        client: OtsClient,
        peripheral: BluetoothPeripheral,
        expectedBoardId: String?,
    ): Boolean {
        val read = runCatching {
            val deviceProto = client.readDeviceObject()

            bluetoothStateManager.updateProgress(
                ConnectionProgress(
                    step1 = StepStatus.COMPLETED,
                    step2 = StepStatus.COMPLETED,
                    step3 = StepStatus.COMPLETED,
                    step4 = StepStatus.IN_PROGRESS
                )
            )

            // The wire copy keeps each enum as a number, so a mode this app does not know neither
            // fails the sync nor gets lost on the next write (M2).
            val profilesWire = client.readDeviceProfiles()

            // DeviceConfig (active profile, boot text, language, screen settings) is a separate
            // writable object the board always exposes; a read failure fails the sync.
            val deviceConfigProto = client.readDeviceConfigObject()

            BoardRead(
                profiles = profilesWire,
                deviceConfig = deviceConfigProto,
                device = boardToDomain(peripheral.name, deviceProto, deviceConfigProto, profilesWire).copy(
                    // The OTS Device object has no firmware version yet, so the read takes it from the
                    // advertisement of this scan key, where the platform gives one (#129).
                    advertisementData = seams.deviceState.value.advertisementData[deviceId],
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.d { "OTS initial read failed for $deviceId: ${error::class.simpleName}: ${error.message}" }
            val current = bluetoothStateManager.connectionProgress.value
            // Only the class name goes into the progress. The message text can hold board data, and
            // the progress goes into a test tag that iOS shows as an accessibility identifier.
            val code = error::class.simpleName ?: UNKNOWN_FAILURE_CODE
            val failedProgress = if (current.step3 == StepStatus.IN_PROGRESS) {
                current.copy(step3 = StepStatus.FAILED, failureCode = code)
            } else {
                current.copy(step4 = StepStatus.FAILED, failureCode = code)
            }
            bluetoothStateManager.updateProgress(failedProgress)
        }.getOrNull()

        if (read == null) {
            // A link with no known board state cannot save, and its later drop would go unseen by
            // the UI, so drop it like a failed OTS init (finding #6). The failed step stays shown.
            teardownConnection(deviceId, disconnectPeripheral = true)
            return false
        }
        val domainDevice = read.device
        val boardId = domainDevice.id.toString()
        bluetoothStateManager.recordBoardId(deviceId, boardId)
        if (expectedBoardId != null && boardId != expectedBoardId) {
            // The scan key did not come from this board (see ensurePeripheral). Keep the other
            // board out of the store and the connected set: the UI asked for a different one.
            // The failure code holds no board id, because it goes into a test tag (see
            // ConnectionProgress.failureCode).
            log.d { "Board $boardId answered on $deviceId, but the connect was for $expectedBoardId" }
            bluetoothStateManager.updateProgress(
                bluetoothStateManager.connectionProgress.value.copy(
                    step4 = StepStatus.FAILED,
                    failureCode = DIFFERENT_BOARD_FAILURE_CODE,
                )
            )
            teardownConnection(deviceId, disconnectPeripheral = true)
            return false
        }
        // Retain the board's last-read Profiles so a later write can preserve per-profile fields
        // the domain model doesn't own (board_auto_off) rather than resetting them to proto
        // defaults (finding #7). Guarded by connectionLock (held here).
        lastReadProfiles[deviceId] = read.profiles
        lastReadDeviceConfig[deviceId] = read.deviceConfig
        bluetoothStateManager.updateProgress(
            ConnectionProgress(
                step1 = StepStatus.COMPLETED,
                step2 = StepStatus.COMPLETED,
                step3 = StepStatus.COMPLETED,
                step4 = StepStatus.COMPLETED
            )
        )
        // A second read on a live link sets the flag again. Clear the old board id first, so no
        // stale id can stay in the connected set.
        markDisconnected(deviceId)
        connectedBoardIds[deviceId] = boardId
        bluetoothStateManager.deviceConnected(boardId)
        log.d { "Read and decoded Device and Profiles from OTS on $deviceId: $domainDevice" }
        deviceReadResultBus.emit(DeviceReadResult(domainDevice, scanKey = deviceId))
        return true
    }

    /**
     * Writes [domainDevice] to the board and always reports the outcome on [deviceWriteResultBus].
     *
     * The write runs on the engine's own [coroutineScope], serialized by [writeMutex], and this call
     * only waits for it. A caller that is cancelled — the Android activity `lifecycleScope` on a
     * rotation — therefore stops waiting but cannot stop the write halfway, with `device_config`
     * written and `profiles` not (M1).
     */
    suspend fun writeDeviceConfiguration(deviceId: String, domainDevice: Device, requestId: Long = 0L) {
        coroutineScope.launch {
            writeMutex.withLock {
                val (sessionKey, peripheral) = connectionLock.withLock {
                    val key = sessionKeyFor(deviceId) ?: deviceId
                    key to liveConnection(key)?.second
                }
                withHighConnectionPriority(peripheral) {
                    performWrite(sessionKey, peripheral?.name, domainDevice, requestId)
                }
            }
        }.join()
    }

    /**
     * Runs [block] with the link at high connection priority, then puts it back to balanced.
     *
     * Each OTS step is one GATT round trip, and at the board's preferred 30-50 ms interval a round
     * trip takes about 100 ms. High priority (an interval of about 7.5-15 ms on Android) makes an
     * OTS read or write several times faster. The link goes back to balanced after, because a short
     * interval costs the board battery for as long as the app stays connected. An engine or a link
     * that refuses the request only leaves the transfer at the slower speed. A null [peripheral]
     * runs [block] with no request.
     */
    private inline fun <T> withHighConnectionPriority(peripheral: BluetoothPeripheral?, block: () -> T): T {
        if (peripheral == null) return block()
        requestConnectionPriority(peripheral, ConnectionPriority.High)
        try {
            return block()
        } finally {
            requestConnectionPriority(peripheral, ConnectionPriority.Balanced)
        }
    }

    private fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        runCatching { blueFalcon.requestConnectionPriority(peripheral, priority) }
            .onFailure { error -> log.d { "Connection priority $priority refused: ${error.message}" } }
    }

    private suspend fun performWrite(
        deviceId: String,
        peripheralName: String?,
        domainDevice: Device,
        requestId: Long,
    ) {
        // Clamp ONCE to the firmware's silently-enforced ranges before doing anything with the
        // device. The firmware never rejects an out-of-range or sentinel value — it bounds/
        // substitutes it and reports success — so if the app sent the raw value the board would
        // store a different one and the app's optimistic baseline would disagree with it forever
        // (findings 3/3b/4/5). By mirroring the firmware here, the wire value, the reported write
        // result, and (via the write-result observer) the editable + baseline device all agree.
        val clamped = domainDevice.clampedToFirmwareRanges()
        // Catch every failure (not connected, OTS object missing, link dropped mid-write) and report
        // it on the write-result bus so the UI can surface it.
        try {
            // Snapshot the client and the board's last-read Profiles and DeviceConfig together under
            // the lock — the maps are mutated on the connect/teardown coroutines. The write itself
            // runs outside the lock so it never blocks a connect/disconnect.
            val (client, knownProfiles, knownDeviceConfig) = connectionLock.withLock {
                Triple(otsClients[deviceId], lastReadProfiles[deviceId], lastReadDeviceConfig[deviceId])
            }
            if (client == null) {
                throw IllegalStateException("Device $deviceId is not connected or OTS not initialized")
            }
            if (knownProfiles == null) {
                // The initial read has not finished (H1). Without the board's Profiles the write
                // would reset board_auto_off in every slot, so refuse it.
                throw IllegalStateException("Device $deviceId has not finished its initial read")
            }
            if (clamped.knownProfiles.size < Device.MIN_PROFILE_COUNT) {
                // A Profiles object with no profiles has zero length. The board refuses a
                // zero-length OACP WRITE, and it also refuses a roster with no profiles. Refuse the
                // write here, before device_config goes, so the board does not keep half a save.
                throw IllegalStateException("Device $deviceId has no profiles; the board keeps at least one")
            }
            // The user can edit the board in its menu while the app is connected, and the board
            // sends no notification for that edit. Read the board again before the write, so a
            // Save cannot overwrite an edit that the app has not seen (#123). A failed read fails
            // the write before it starts.
            val boardProfiles = client.readDeviceProfiles()
            val boardDeviceConfig = client.readDeviceConfigObject()
            if (showsABoardEdit(knownProfiles, knownDeviceConfig, boardProfiles, boardDeviceConfig)) {
                // The read of the read-only object gives the read result the current lockout.
                val deviceProto = client.readDeviceObject()
                connectionLock.withLock {
                    if (otsClients[deviceId] === client) {
                        lastReadProfiles[deviceId] = boardProfiles
                        lastReadDeviceConfig[deviceId] = boardDeviceConfig
                    }
                }
                log.d { "Board $deviceId changed since the last read; the write does not start" }
                // Send the board state on the read bus, so the sync rules raise the conflict. The
                // failed write result then ends the Save. The two buses have different
                // collectors, so this sets only the order of the emits.
                deviceReadResultBus.emit(
                    DeviceReadResult(
                        boardToDomain(peripheralName, deviceProto, boardDeviceConfig, boardProfiles),
                        scanKey = deviceId,
                    )
                )
                throw BoardChangedBeforeWriteException(deviceId)
            }
            // Preserve board-owned per-profile fields the domain model doesn't carry
            // (board_auto_off) and unknown enum numbers, each from the slot the profile came from.
            // They come from the read above, so a change to one of them in the board menu stays.
            val profiles = toProtocolProfilesWire(clamped, boardProfiles)
            try {
                client.writeDeviceConfiguration(
                    deviceConfig = toProtocolDeviceConfig(clamped),
                    profiles = profiles
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // A concurrent teardown (link-loss monitor / disconnect) can close this client
                // mid-write, disabling the indications the second object's OACP handshake needs and
                // leaving device_config written but profiles stale. Detect that race — the client is
                // no longer the active one — and surface a distinct "link lost during save" error so
                // the UI knows the save may be partial, instead of a generic OACP timeout (#45).
                val stillActive = connectionLock.withLock {
                    (otsClients[deviceId] === client).also { active ->
                        // device_config can be written although the write failed after it. The
                        // copy from before the write is then out of date, and a retry would take
                        // this write for a board-menu edit. So the next write does not compare
                        // DeviceConfig.
                        if (active) lastReadDeviceConfig.remove(deviceId)
                    }
                }
                if (!stillActive) {
                    throw IllegalStateException(
                        "Link to $deviceId lost during save; configuration may be partially written",
                        error
                    )
                }
                throw error
            }
            log.d { "Wrote device configuration to $deviceId" }
            // An UNKNOWN mode went out as the board's own number, so record the mode that it is.
            // The board now holds knownProfiles[i] in slot i, so each profile takes that slot as its
            // source slot. A later delete or add then still knows the slot of each profile.
            val written = clamped.copy(knownProfiles = clamped.knownProfiles.withModesAsWritten(profiles))
                .withProfilesInWrittenSlots()
            // The board ignores a language or a brightness that it does not know, and it clamps
            // the screen-off delay. Firmware that predates the display window stores no rectangle.
            // The protocol says that a client must not assume the write took effect, so read every
            // DeviceConfig setting back and report those (L15). Without the display window here the
            // baseline would hold a rectangle the board never kept, and every later read would
            // raise a conflict the user has to resolve by hand. The write itself succeeded, so a
            // failed read-back keeps the values that were written.
            val readBack = readBackOrNull(deviceId, "device_config") {
                client.readDeviceConfigObjectAfterCommit()
            }
            // Read the Profiles back too. The next write compares the board with this copy, so it
            // must be what the board holds and not what the app sent: a value that the board
            // changed on commit would otherwise look like a board-menu edit.
            val profilesReadBack = readBackOrNull(deviceId, "profiles") { client.readDeviceProfiles() }
            // The board now holds these Profiles in these slots. Keep them as the last-read copy so
            // a second save in this session pairs its slots with the board's current ones. With no
            // read-back of device_config, the copy from before the write is out of date, so the
            // next write does not compare DeviceConfig. With no read-back of profiles, the sent
            // copy is used: firmware that changes a profile value on commit can then show one
            // false conflict at the next Save.
            connectionLock.withLock {
                if (otsClients[deviceId] === client) {
                    lastReadProfiles[deviceId] = profilesReadBack ?: profiles
                    if (readBack != null) {
                        lastReadDeviceConfig[deviceId] = readBack
                    } else {
                        lastReadDeviceConfig.remove(deviceId)
                    }
                }
            }
            val stored = readBack?.let { back ->
                written.copy(
                    activeProfileId = back.activeProfileSlot(written.knownProfiles.size)
                        ?.let { slot -> written.knownProfiles[slot].id },
                    bootText = back.bootTextToDomain(),
                    language = back.language.toDomain(),
                    screenBrightness = back.screenBrightness.toDomain(),
                    screenOffDelay = back.screenOffDelay.screenOffDelayToDomain(),
                    displayWindow = back.displayWindow?.toDomain(),
                )
            } ?: written
            // Report the CLAMPED device (what the board stored) plus the pre-clamp requested device,
            // so the observer reconciles the editable + baseline to what the board actually stored
            // without clobbering an edit made while this write was in flight.
            deviceWriteResultBus.emit(
                DeviceWriteResult(stored, error = null, requestedDevice = domainDevice, requestId = requestId)
            )
        } catch (cancellation: CancellationException) {
            // Only a cancelled engine scope gets here. Report the write as failed all the same, so
            // the UI never waits for a result that does not come (M1).
            withContext(NonCancellable) {
                deviceWriteResultBus.emit(
                    DeviceWriteResult(
                        clamped,
                        error = cancellation,
                        requestedDevice = domainDevice,
                        requestId = requestId,
                    )
                )
            }
            throw cancellation
        } catch (error: Exception) {
            if (error !is BoardChangedBeforeWriteException) {
                log.e(error) { "Failed to write device configuration to $deviceId" }
            }
            deviceWriteResultBus.emit(
                DeviceWriteResult(clamped, error = error, requestedDevice = domainDevice, requestId = requestId)
            )
        }
    }

    /**
     * True when the board's new read of `profiles` and `device_config` shows an edit that the app
     * has not seen since [knownProfiles] and [knownDeviceConfig]. With no [knownDeviceConfig] (its
     * read-back failed), only the Profiles are compared.
     *
     * The comparison uses the fields that the app edits and the sync rules compare, not the wire
     * copies. A change to a board-owned field only (`board_auto_off`), or between two enum numbers
     * that this app does not know, is not an edit that the user can see or resolve. The write then
     * continues and keeps the new value.
     */
    private fun showsABoardEdit(
        knownProfiles: ProfilesWire,
        knownDeviceConfig: DeviceConfig?,
        boardProfiles: ProfilesWire,
        boardDeviceConfig: DeviceConfig,
    ): Boolean {
        val sameConfig = knownDeviceConfig == null || boardDeviceConfig == knownDeviceConfig
        if (boardProfiles == knownProfiles && sameConfig) return false
        val known = editableFields(knownDeviceConfig ?: boardDeviceConfig, knownProfiles)
        return editableFields(boardDeviceConfig, boardProfiles) != known
    }

    /**
     * The fields of a board read that the app edits, as a [BoardState]. The read-only `device`
     * object adds no editable field, so a placeholder serves here, and the lockout stays false. The
     * profile ids come from the device id, and both reads of a comparison use the same placeholder
     * id, so their profile ids agree.
     * `MarkerProfile.sourceSlot` is not a field that the user edits, and the divergence checks
     * ignore it (`MarkerProfile.equalsIgnoringSourceSlot`), so it is cleared here.
     */
    private fun editableFields(deviceConfig: DeviceConfig, profiles: ProfilesWire): BoardState =
        boardToDomain(null, COMPARISON_DEVICE, deviceConfig, profiles).let { device ->
            BoardState(
                bootText = device.bootText,
                activeProfileId = device.activeProfileId,
                lockoutEnabled = false,
                knownProfiles = device.knownProfiles.map { it.copy(sourceSlot = null) },
                language = device.language,
                screenBrightness = device.screenBrightness,
                screenOffDelay = device.screenOffDelay,
                displayWindow = device.displayWindow,
            )
        }

    /** Runs the read-back [read] of [objectName], and returns `null` with a log line when it fails. */
    private suspend inline fun <T : Any> readBackOrNull(
        deviceId: String,
        objectName: String,
        read: () -> T,
    ): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.d { "Read-back of $objectName after the write to $deviceId failed: ${error.message}" }
        null
    }

    /** Decodes the three board objects into the domain device that a read result carries. */
    private fun boardToDomain(
        peripheralName: String?,
        deviceProto: snapshotpb.v1.Device,
        deviceConfig: DeviceConfig,
        profiles: ProfilesWire,
    ): Device = toDomainDevice(
        protoDevice = deviceProto,
        protoDeviceConfig = deviceConfig,
        protoProfiles = profiles.toGenerated(),
        deviceName = peripheralName ?: FALLBACK_DEVICE_NAME,
        lastSeenDate = getCurrentLocalDate(),
    )

    /** One read of the board: the two writable objects as the board holds them, and the domain device. */
    private class BoardRead(
        val profiles: ProfilesWire,
        val deviceConfig: DeviceConfig,
        val device: Device,
    )

    private suspend fun indexOtsObjects(deviceId: String, client: OtsClient) {
        // Records each object's ID so that each later selection is one OLCP Go To (see
        // OtsClient.selectObjectByName). A failure costs only speed: the selections fall back to the
        // First/Next walk. Runs inside connectionLock (via initializeOtsClient), so it takes the
        // client as a parameter and does not go through the public listOtsObjects, which
        // re-acquires the non-reentrant lock and would deadlock (finding #47).
        val ids = runCatching { client.indexObjects() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log.d { "OTS indexObjects failed for $deviceId: ${error.message}" }
            }
            .getOrDefault(emptyMap())
        if (ids.isEmpty()) {
            log.d { "OTS $deviceId: no objects" }
            return
        }
        log.d { "OTS $deviceId: ${ids.size} object(s)" }
        ids.forEach { (name, id) ->
            log.d { "OTS $deviceId: id=0x${id.toString(16).padStart(12, '0')} name=\"$name\"" }
        }
    }

    /**
     * Subscribes to every notifying characteristic on a freshly connected peripheral and
     * records the inbound traffic in [bleDebugLog] for protocol debugging.
     *
     * This traffic tells the app nothing about the board state. The firmware sends no notification
     * when the user edits the board in its menu: its only notifications are the OTS control-point
     * responses, the object data and the log lines. The write path therefore compares the board with
     * the last read before each write (see [showsABoardEdit]).
     */
    private fun observeForDebug(peripheral: BluetoothPeripheral) {
        val deviceId = peripheral.uuid.toDeviceUuid().toString()
        debugJobs.remove(deviceId)?.forEach(Job::cancel)
        bleDebugLog.clear(deviceId)

        val notifiable = peripheral.characteristics.filter { it.isNotifying }
        if (notifiable.isEmpty()) {
            log.d { "No notifying characteristics to observe on $deviceId" }
            return
        }

        log.d { "Observing ${notifiable.size} characteristic(s) on $deviceId for debugging" }
        debugJobs[deviceId] = notifiable.map { characteristic ->
            coroutineScope.launch {
                runCatching { blueFalcon.notifyCharacteristic(peripheral, characteristic, true) }
                    .onFailure { error ->
                        log.d { "Failed to enable notifications for ${characteristic.uuid}: ${error.message}" }
                    }
                characteristic.notifications.collect { bytes ->
                    val event = BleDebugEvent(
                        timestampMillis = Clock.System.now().toEpochMilliseconds(),
                        deviceId = deviceId,
                        characteristicUuid = characteristic.uuid.toString(),
                        bytes = bytes,
                    )
                    bleDebugLog.record(event)
                    log.d { "BLE <- $deviceId / ${characteristic.uuid}: ${seams.formatDebugBytes(event)}" }
                }
            }
        }
    }

    /**
     * Reads the firmware update service of the board on [sessionKey] after the 4-step connect, and
     * records it in [BluetoothStateManager.updateServices]. This is not a fifth connect step: the
     * board is connected already, and no result of this read fails the connection. A board with
     * no SMP service, an engine with no typed write, an SMP error and a time-out each give an entry
     * with no target and a log line.
     *
     * A live link that has a session keeps it, so a second read of the board does no SMP command.
     * Must be called while [writeMutex] and [connectionLock] are held. The UI shows the board as
     * connected during this read, so a Save in this time waits up to
     * [UPDATE_SERVICE_READ_LIMIT_MILLIS]; the entry shows [UpdateServiceStatus.CHECKING] meanwhile.
     */
    private suspend fun readUpdateService(sessionKey: String, peripheral: BluetoothPeripheral) {
        val boardId = connectedBoardIds[sessionKey] ?: return
        smpSessions[sessionKey]?.let { session ->
            // A session whose image list failed reads it again, so a verify after an update can
            // see the image that runs.
            if (session.service.activeImage == null) probeAgain(boardId, session)
            publishUpdateService(sessionKey, boardId, session.service)
            return
        }
        if (!hasSmpService(peripheral)) {
            log.d { "Board $boardId has no SMP service, so it gets no firmware update" }
            publishUpdateService(sessionKey, boardId, BoardUpdateService(UpdateServiceStatus.ABSENT))
            return
        }
        publishUpdateService(sessionKey, boardId, BoardUpdateService(UpdateServiceStatus.CHECKING))
        val service = try {
            withTimeoutOrNull(UPDATE_SERVICE_READ_LIMIT_MILLIS) { openUpdateService(sessionKey, peripheral) }
                ?: BoardUpdateService(UpdateServiceStatus.FAILED).also {
                    log.d { "The SMP read of $boardId did not end in $UPDATE_SERVICE_READ_LIMIT_MILLIS ms" }
                }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { closeUpdateService(sessionKey) }
            throw cancelled
        } catch (error: SmpWriteUnsupported) {
            log.d { "The Bluetooth engine cannot write to SMP: ${error.message}" }
            BoardUpdateService(UpdateServiceStatus.UNSUPPORTED_PLATFORM)
        } catch (error: Exception) {
            log.d { "The SMP read of $boardId failed: ${error::class.simpleName}: ${error.message}" }
            BoardUpdateService(UpdateServiceStatus.FAILED)
        }
        if (service.status != UpdateServiceStatus.AVAILABLE) smpSessions.remove(sessionKey)?.close()
        log.d { "Update service of $boardId: $service" }
        publishUpdateService(sessionKey, boardId, service)
    }

    /** Probes [session] again. A failure keeps the last result, with a log line. */
    private suspend fun probeAgain(boardId: String, session: BoardFirmwareSession) {
        try {
            withTimeoutOrNull(UPDATE_SERVICE_READ_LIMIT_MILLIS) { session.probe() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.d { "The second SMP read of $boardId failed: ${error::class.simpleName}: ${error.message}" }
        }
    }

    /** Opens the SMP transport of [peripheral], keeps the session for [sessionKey], and probes it. */
    private suspend fun openUpdateService(sessionKey: String, peripheral: BluetoothPeripheral): BoardUpdateService {
        val opened = BlueFalconSmpTransport.open(blueFalcon, peripheral) { characteristic ->
            seams.awaitSubscribed(peripheral, characteristic)
        }
        return when (opened) {
            SmpOpenResult.NoSmpService -> BoardUpdateService(UpdateServiceStatus.ABSENT)
            is SmpOpenResult.Opened -> {
                val session = BoardFirmwareSession(opened.transport) {
                    withTimeoutOrNull(SMP_CLOSE_LIMIT_MILLIS) { opened.transport.close() }
                }
                // Keep the session first, so a cancelled read still closes it.
                smpSessions[sessionKey] = session
                session.probe()
            }
        }
    }

    /** Must be called while [connectionLock] is held. */
    private fun publishUpdateService(sessionKey: String, boardId: String, service: BoardUpdateService) {
        updateServiceBoardIds[sessionKey] = boardId
        bluetoothStateManager.recordUpdateService(boardId, service)
    }

    /** Closes the SMP session of [sessionKey] and removes its entry. Must be called while [connectionLock] is held. */
    private suspend fun closeUpdateService(sessionKey: String) {
        smpSessions.remove(sessionKey)?.close()
        updateServiceBoardIds.remove(sessionKey)?.let(bluetoothStateManager::clearUpdateService)
    }

    /**
     * Sends [image] to the board [deviceId] over SMP: `image list`, the upload with the buffer size
     * of the board, `image list` for the hash of slot 1, `image test` and `reset`. The call returns
     * at once, and the upload runs on [coroutineScope]. Each step goes to
     * [BluetoothStateManager.firmwareUpload] with [requestId]. The last state is
     * [FirmwareUpdateProgress.AwaitingSwap] with the uploaded hash, or [FirmwareUpdateProgress.Failed].
     *
     * The upload holds [writeMutex] from start to end, so no OTS write and no connect read runs at
     * the same time, and no write compares the configuration while the board holds fire control.
     * The link runs at high connection priority during the upload. A [disconnect] stops the upload
     * with [FirmwareUpdateFailure.LINK_LOST].
     */
    fun updateFirmware(deviceId: String, image: FirmwareImage, requestId: Long = 0L) {
        // The first state goes out before the lock: a connect read can hold the lock for some
        // time, and the caller must know that the request arrived.
        firmwareReporter(deviceId, requestId)(FirmwareUpdateProgress.Preparing, null)
        coroutineScope.launch { writeMutex.withLock { performFirmwareUpload(deviceId, image, requestId) } }
    }

    /**
     * Confirms the image that runs on the board [deviceId] when it is not confirmed (see
     * [BoardFirmwareSession.confirmActiveImage]). The call returns at once. The result goes to
     * [BluetoothStateManager.firmwareUpload] with [requestId]: [FirmwareUpdateProgress.Done] with the
     * version of the image, or [FirmwareUpdateProgress.Failed].
     */
    fun confirmFirmware(deviceId: String, requestId: Long = 0L) {
        firmwareReporter(deviceId, requestId)(FirmwareUpdateProgress.Verifying, null)
        coroutineScope.launch { writeMutex.withLock { performFirmwareConfirm(deviceId, requestId) } }
    }

    private suspend fun performFirmwareUpload(deviceId: String, image: FirmwareImage, requestId: Long) {
        val report = firmwareReporter(deviceId, requestId)
        val link = firmwareLink(deviceId) ?: return report(failed(FirmwareUpdateFailure.NOT_CONNECTED), null)
        val session = link.session ?: return report(failed(FirmwareUpdateFailure.NO_UPDATE_SERVICE), null)
        val sha256 = decodeSha256(image.manifest.assets.image.sha256)
            ?: return report(failed(FirmwareUpdateFailure.BAD_IMAGE), null)
        try {
            val hash = withHighConnectionPriority(link.peripheral) {
                session.upload(
                    image = image.bytes,
                    sha256 = sha256,
                    onProgress = { progress ->
                        report(FirmwareUpdateProgress.Uploading(progress.offset, progress.total), null)
                    },
                    onResetting = { report(FirmwareUpdateProgress.Resetting, null) },
                )
            }
            log.d { "Firmware upload to $deviceId done; the board resets" }
            report(FirmwareUpdateProgress.AwaitingSwap(), hash.toHex())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { report(failed(FirmwareUpdateFailure.CANCELLED), null) }
            throw cancelled
        } catch (error: Exception) {
            log.e(error) { "Firmware upload to $deviceId failed" }
            report(failed(firmwareFailureOf(error, link.isStillLive(session)), firmwareFailureCode(error)), null)
        }
    }

    private suspend fun performFirmwareConfirm(deviceId: String, requestId: Long) {
        val report = firmwareReporter(deviceId, requestId)
        val link = firmwareLink(deviceId) ?: return report(failed(FirmwareUpdateFailure.NOT_CONNECTED), null)
        val session = link.session ?: return report(failed(FirmwareUpdateFailure.NO_UPDATE_SERVICE), null)
        try {
            val image = session.confirmActiveImage()
            connectionLock.withLock {
                updateServiceBoardIds[link.sessionKey]?.let { boardId ->
                    if (smpSessions[link.sessionKey] === session) {
                        bluetoothStateManager.recordUpdateService(boardId, session.service)
                    }
                }
            }
            report(FirmwareUpdateProgress.Done(image.firmwareVersion), null)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { report(failed(FirmwareUpdateFailure.CANCELLED), null) }
            throw cancelled
        } catch (error: Exception) {
            log.e(error) { "Firmware confirm on $deviceId failed" }
            report(failed(firmwareFailureOf(error, link.isStillLive(session)), firmwareFailureCode(error)), null)
        }
    }

    /** A function that puts one state of the request [requestId] on [BluetoothStateManager.firmwareUpload]. */
    private fun firmwareReporter(deviceId: String, requestId: Long): (FirmwareUpdateProgress, String?) -> Unit =
        { progress, hash ->
            bluetoothStateManager.updateFirmwareUpload(FirmwareUploadState(requestId, deviceId, progress, hash))
        }

    private fun failed(reason: FirmwareUpdateFailure, detail: String? = null) =
        FirmwareUpdateProgress.Failed(reason, detail)

    /**
     * The live link and the SMP session of [deviceId], or `null` with a log line when the board is
     * not connected. The session is `null` when the board has no update service.
     */
    private suspend fun firmwareLink(deviceId: String): FirmwareLink? = connectionLock.withLock {
        val key = sessionKeyFor(deviceId) ?: deviceId
        val peripheral = liveConnection(key)?.second
        if (peripheral == null) {
            log.d { "Firmware request for $deviceId, but the board is not connected" }
            null
        } else {
            FirmwareLink(key, peripheral, smpSessions[key]?.takeIf { it.service.status == UpdateServiceStatus.AVAILABLE })
        }
    }

    /** The link of a firmware step: the scan key, the peripheral and the SMP session. */
    private inner class FirmwareLink(
        val sessionKey: String,
        val peripheral: BluetoothPeripheral,
        val session: BoardFirmwareSession?,
    ) {
        /** True when the link is still up and [session] is still the session of the link. */
        suspend fun isStillLive(session: BoardFirmwareSession): Boolean = connectionLock.withLock {
            liveConnection(sessionKey) != null && smpSessions[sessionKey] === session
        }
    }

    /** The 32 bytes of a SHA-256 in hexadecimal text, or `null` for text that is not one. */
    private fun decodeSha256(hex: String): ByteArray? {
        if (hex.length != SHA256_HEX_LENGTH) return null
        val bytes = ByteArray(SHA256_HEX_LENGTH / 2)
        for (index in bytes.indices) {
            val high = hex[index * 2].digitToIntOrNull(HEX_RADIX) ?: return null
            val low = hex[index * 2 + 1].digitToIntOrNull(HEX_RADIX) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    companion object {
        /**
         * The time limit of the SMP read after a connect: the subscription and three commands. The
         * SMP client has a limit of 5 s for each answer.
         */
        private const val UPDATE_SERVICE_READ_LIMIT_MILLIS = 20_000L

        /** The time limit of the removal of the SMP subscription: a closed link must not hold the teardown. */
        private const val SMP_CLOSE_LIMIT_MILLIS = 2_000L

        private const val SHA256_HEX_LENGTH = 64
        private const val HEX_RADIX = 16

        private const val SCAN_FOR_CONNECT_TIMEOUT_MILLIS = 12_000L
        private const val SCAN_FOR_CONNECT_POLL_INTERVAL_MILLIS = 500L
        private const val CONNECTION_MONITOR_INTERVAL_MILLIS = 2_500L

        /** Cap on the first-discovery log marker set; past this, discovery lines are simply not repeated. */
        private const val MAX_LOGGED_PERIPHERAL_IDS = 512

        /** Connect attempts before the engine reports a failure. A safety net for a rare connect failure. */
        private const val CONNECT_ATTEMPTS = 3

        /** Per-attempt cap on opening the link + discovering services; a dropped link retries after this. */
        private const val CONNECT_ATTEMPT_TIMEOUT_MILLIS = 5_000L

        /** Backoff between connect attempts (the board needs a moment to re-advertise after a drop). */
        private const val CONNECT_RETRY_BACKOFF_MILLIS = 750L

        /**
         * Name of a new device when the peripheral reports none. It is the name the board advertises,
         * the same as `BOARD_ADVERTISED_NAME` in `core:common`, which this module cannot import.
         */
        private const val FALLBACK_DEVICE_NAME = "Snapshot PB"

        /**
         * How long a connect to a board id with no scan key waits for its own scan key before it
         * takes a board whose board id this session does not know (see [ensurePeripheral]).
         */
        private const val UNREAD_BOARD_WAIT_MILLIS = 2_000L

        /** The [ConnectionProgress.failureCode] of an exception that has no simple class name. */
        private const val UNKNOWN_FAILURE_CODE = "Unknown"

        /**
         * The `device` object that [editableFields] decodes with. The mapper refuses a `device_id`
         * that is not 16 bytes, so the placeholder has 16 zero bytes.
         */
        private val COMPARISON_DEVICE = snapshotpb.v1.Device(deviceId = ByteArray(DEVICE_ID_BYTES))

        /** The length of the board `device_id`, a UUID. */
        private const val DEVICE_ID_BYTES = 16

        /** The [ConnectionProgress.failureCode] when a different board answered a connect by board id. */
        private const val DIFFERENT_BOARD_FAILURE_CODE = "DifferentBoard"

        private val log = Arbor.tag("BluetoothManager")

        /**
         * The scope the engine uses when its owner supplies none. A [SupervisorJob] keeps one failed
         * coroutine from cancelling the others (the BlueFalcon collectors, the link monitors, a
         * connect or a write), and the handler logs the failure instead of letting it crash the
         * process (L18).
         */
        fun defaultScope(): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                    log.e(error) { "Unhandled failure in the Bluetooth engine" }
                }
            )
    }
}