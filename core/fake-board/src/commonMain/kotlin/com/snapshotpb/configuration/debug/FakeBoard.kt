package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.SnapshotAdvertisement
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.bluetooth.ots.getCurrentLocalDate
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.bluetooth.ots.toDomainDevice
import com.snapshotpb.configuration.bluetooth.ots.toProtocolDeviceConfig
import com.snapshotpb.configuration.bluetooth.ots.toProtocolProfiles
import com.snapshotpb.configuration.common.screen.device.BOARD_ADVERTISED_NAME
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.withProfilesInWrittenSlots
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import snapshotpb.v1.BoardModel
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.Language
import snapshotpb.v1.Profiles
import snapshotpb.v1.ScreenBrightness
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * A simulated Snapshot PB board for a debug build. It replaces the radio, not the board: the app
 * still drives the same request bus, the same connect steps and the same read and write result
 * buses, so device discovery, the 4-step connect flow, profile edits, divergence badges and the
 * sync dialogs all behave as they do against a real marker.
 *
 * The board holds its configuration in the PROTOCOL's own messages, and it reads and writes them
 * through the same mappers the real engine uses. That is what makes a divergence realistic: a boot
 * text longer than the firmware buffer comes back cut, a profile id comes back derived from its
 * slot, and an unset language, brightness, screen-off delay or display window leaves the board's own
 * value alone. What it does NOT exercise is everything below the mappers: BlueFalcon, the OTS
 * control points, and the wire itself. Those need a real peripheral.
 *
 * The Demo entry in the launcher starts the app with this board; the normal entry starts it with
 * the real radio. A shell does the same:
 * ```
 * adb shell am start -n com.snapshotpb.configuration/.activity.DemoActivity
 * adb shell am start -S -n com.snapshotpb.configuration/.activity.MainActivity
 * ```
 * The switch does not persist. Each launch carries its own, so the entry you tap decides the mode.
 *
 * THREADING: [handle] gives the slow work to [scope] and returns at once. The request bus has no
 * buffer, so work done in the caller would hold the bus and block the Cancel affordance, and an
 * activity teardown mid-connect would cancel the attempt. [boardLock] then serializes that work, so
 * [boardConfig] and [boardProfiles] change one operation at a time. Everything here runs on the main
 * thread. The switch is `@Volatile` because a future caller on another thread would otherwise read
 * a stale one.
 */
internal class FakeBoard(
    private val bluetoothStateManager: BluetoothStateManager,
    private val deviceReadResultBus: DeviceReadResultBus,
    private val deviceWriteResultBus: DeviceWriteResultBus,
) : DebugBoard {

    @Volatile
    private var enabled: Boolean = false

    override val active: Boolean get() = enabled

    /** What the board holds, in the form the board holds it. A write updates both messages. */
    private var boardConfig: DeviceConfig = toProtocolDeviceConfig(fakeBoardSeed())

    private var boardProfiles: Profiles = toProtocolProfiles(fakeBoardSeed())

    private val discovered: MutableStateFlow<BluetoothDeviceState> =
        MutableStateFlow(BluetoothDeviceState())

    /**
     * Scope of the board's own work. The board is a singleton that lives as long as the process, so
     * the scope needs no owner to cancel it, and work on it survives an activity teardown. The one
     * job that must stop when the switch goes off is the state guard, and [releaseBluetoothState]
     * cancels that one.
     */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Serializes the board's work, the way the real engine serializes with its connection lock. */
    private val boardLock = Mutex()

    private var stateGuard: Job? = null

    private var connectionJob: Job? = null

    override fun applySwitch(on: Boolean?) {
        if (on != null && on != enabled) {
            enabled = on
            log.d { "Fake board switch is now $enabled" }
        }
        if (enabled) takeOverBluetoothState() else releaseBluetoothState()
    }

    /**
     * Holds the Bluetooth state at ready while the fake board is on.
     *
     * This is the second of two guards, and both are needed. The activity skips its own permission
     * report while the board is active, which covers the calls the activity makes. This collector
     * covers the other source: the real manager still exists, because the device browser reads its
     * scan flow, so its BlueFalcon collector reports the emulator's radio a moment after launch and
     * drives the state to [BluetoothState.NeedsPermission] with no permission granted. The UI then
     * offers a permission prompt in place of the scan.
     *
     * The collector cannot drive a loop. The Android seam derives the state only in its own
     * permission, location and manager-state callbacks. It never reads this flow back.
     */
    private fun takeOverBluetoothState() {
        if (stateGuard != null) return
        stateGuard = scope.launch {
            bluetoothStateManager.bluetoothState.collect { state ->
                val blocked = state == BluetoothState.NeedsPermission ||
                    state == BluetoothState.PermissionDenied ||
                    state == BluetoothState.Disabled ||
                    state == BluetoothState.LocationDisabled
                // An attempt in flight owns the state. Without this guard a radio report that lands
                // between two steps drops the connection card back to ready.
                if (blocked && connectionJob?.isActive != true) {
                    log.d { "Fake board replaces $state with Ready" }
                    bluetoothStateManager.update(BluetoothState.Ready)
                }
            }
        }
    }

    /**
     * Hands the state back to the real manager and drops the board from the scan results. The
     * activity reports the real radio right after it applies a switch that is off, and that report
     * is what puts a true state back on the flow.
     *
     * A fake board that was connected or connecting is disconnected here too. Without this the
     * device stays in the connected set after the switch goes off, and the real app shows a board
     * that no radio holds (L19). The activity applies the switch on every launch, so the reset runs
     * only when the fake board holds something: it must not clear the progress of a real connect.
     */
    private fun releaseBluetoothState() {
        val fakeWasBusy = connectionJob?.isActive == true ||
            bluetoothStateManager.connectedDevices.value.contains(FAKE_DEVICE_ID)
        connectionJob?.cancel()
        connectionJob = null
        stateGuard?.cancel()
        stateGuard = null
        discovered.value = BluetoothDeviceState()
        if (fakeWasBusy) {
            onBoard { disconnect(FAKE_DEVICE_ID) }
        }
    }

    override suspend fun handle(request: BluetoothRequest): Boolean {
        if (!enabled) return false
        log.d { "Fake board answers $request" }
        when (request) {
            BluetoothRequest.EnableBluetooth,
            BluetoothRequest.EnableLocation,
            BluetoothRequest.RequestPermission ->
                bluetoothStateManager.update(BluetoothState.Ready)

            BluetoothRequest.StartScan -> onBoard { startScan() }

            BluetoothRequest.StopScan ->
                if (bluetoothStateManager.bluetoothState.value == BluetoothState.Scanning) {
                    bluetoothStateManager.update(BluetoothState.Ready)
                }

            is BluetoothRequest.Connect -> {
                // One attempt at a time, and a Disconnect cancels the one in flight — the rule the
                // real engine keeps with its own connection job.
                connectionJob?.cancel()
                connectionJob = onBoard { connect(request.deviceId) }
            }

            is BluetoothRequest.Disconnect -> {
                // Cancel the attempt first: it holds the board lock, so the disconnect below would
                // otherwise wait for the whole connect to finish.
                connectionJob?.cancel()
                connectionJob = null
                onBoard { disconnect(request.deviceId) }
            }
            is BluetoothRequest.WriteDevice ->
                onBoard { write(request.deviceId, request.device, request.requestId) }
        }
        return true
    }

    /** Runs [block] on the board's own scope, after every other operation on it has finished. */
    private fun onBoard(block: suspend () -> Unit): Job =
        scope.launch { boardLock.withLock { block() } }

    override fun deviceState(real: Flow<BluetoothDeviceState>): Flow<BluetoothDeviceState> =
        real.combine(discovered) { realState, fakeState ->
            realState.copy(
                devices = realState.devices + fakeState.devices,
                advertisementData = realState.advertisementData + fakeState.advertisementData,
            )
        }

    private suspend fun startScan() {
        if (bluetoothStateManager.bluetoothState.value != BluetoothState.Ready) return
        bluetoothStateManager.update(BluetoothState.Scanning)
        delay(SCAN_DELAY_MILLIS)
        discovered.value = advertisedState()
    }

    /**
     * Walks the same four connect steps the real engine reports, then puts the board's own
     * configuration on the read bus. The sync delegate takes it from there, so a first connect
     * offers the board as a new device, and a later one raises a conflict when the two disagree.
     */
    private suspend fun connect(deviceId: String) {
        try {
            if (bluetoothStateManager.bluetoothState.value == BluetoothState.Scanning) {
                bluetoothStateManager.update(BluetoothState.Ready)
            }
            bluetoothStateManager.update(BluetoothState.Connecting(deviceId))
            bluetoothStateManager.resetProgress()
            bluetoothStateManager.updateProgress(ConnectionProgress(step1 = StepStatus.IN_PROGRESS))

            if (deviceId != FAKE_DEVICE_ID) {
                // Only the simulated board exists while the fake is on. Fail like the real engine
                // does when the peripheral never turns up, rather than answer for another board.
                log.d { "Fake board cannot connect $deviceId; only $FAKE_DEVICE_ID exists" }
                bluetoothStateManager.updateProgress(
                    ConnectionProgress(
                        step1 = StepStatus.FAILED,
                        failureCode = "UnknownDevice",
                    )
                )
                bluetoothStateManager.update(BluetoothState.Ready)
                return
            }

            // A connect from the Profiles screen skips the browser, so publish the board here too.
            discovered.value = advertisedState()
            delay(STEP_DELAY_MILLIS)
            bluetoothStateManager.updateProgress(
                ConnectionProgress(step1 = StepStatus.COMPLETED, step2 = StepStatus.IN_PROGRESS)
            )

            delay(STEP_DELAY_MILLIS)
            bluetoothStateManager.updateProgress(
                ConnectionProgress(
                    step1 = StepStatus.COMPLETED,
                    step2 = StepStatus.COMPLETED,
                    step3 = StepStatus.IN_PROGRESS,
                )
            )

            delay(STEP_DELAY_MILLIS)
            bluetoothStateManager.updateProgress(
                ConnectionProgress(
                    step1 = StepStatus.COMPLETED,
                    step2 = StepStatus.COMPLETED,
                    step3 = StepStatus.COMPLETED,
                    step4 = StepStatus.IN_PROGRESS,
                )
            )

            delay(STEP_DELAY_MILLIS)
            bluetoothStateManager.updateProgress(
                ConnectionProgress(
                    step1 = StepStatus.COMPLETED,
                    step2 = StepStatus.COMPLETED,
                    step3 = StepStatus.COMPLETED,
                    step4 = StepStatus.COMPLETED,
                )
            )
            // Like the real engine, mark the board connected only after the read, so Save stays
            // off until the board state is known.
            bluetoothStateManager.deviceConnected(deviceId)
            deviceReadResultBus.emit(DeviceReadResult(readBoard(), scanKey = deviceId))
            bluetoothStateManager.update(BluetoothState.Ready)
        } finally {
            // A cancelled attempt — a Disconnect, or a second Connect — would otherwise leave the
            // card at "connecting" for good. The engine clears it the same way, under the same guard.
            val state = bluetoothStateManager.bluetoothState.value
            if (state is BluetoothState.Connecting && state.deviceId == deviceId) {
                bluetoothStateManager.update(BluetoothState.Ready)
                bluetoothStateManager.resetProgress()
            }
        }
    }

    /** Runs under [boardLock] (see [onBoard]), so it cannot interleave with a write. */
    private fun disconnect(deviceId: String) {
        bluetoothStateManager.deviceDisconnected(deviceId)
        bluetoothStateManager.resetProgress()
    }

    /**
     * Stores a write the way the board stores one. The value goes out through the same protocol
     * mapper the real write uses, so the board keeps the cut boot text and the slot order that a
     * real one keeps, and an unset language, brightness, screen-off delay or display window leaves
     * its own value alone. The result then reports what the board kept, which is the read-back the
     * engine does after a commit. Without that the app caches a value the board never held.
     */
    private suspend fun write(deviceId: String, device: Device, requestId: Long) {
        val clamped = device.clampedToFirmwareRanges()
        val refusal = when {
            deviceId != FAKE_DEVICE_ID -> "Device $deviceId is not the fake board"
            !bluetoothStateManager.connectedDevices.value.contains(deviceId) ->
                "Device $deviceId is not connected or OTS not initialized"

            else -> null
        }
        if (refusal != null) {
            log.d { "Fake board refuses the write: $refusal" }
            deviceWriteResultBus.emit(
                DeviceWriteResult(clamped, IllegalStateException(refusal), device, requestId)
            )
            return
        }

        delay(WRITE_DELAY_MILLIS)
        val written = toProtocolDeviceConfig(clamped)
        boardConfig = boardConfig.copy(
            activeProfile = written.activeProfile,
            bootText = written.bootText,
            // The board ignores a value it does not know, and the mapper sends the unset value for
            // one the app does not hold, so each of these four keeps the board's own value.
            language = written.language
                .takeUnless { it == Language.LANGUAGE_UNSPECIFIED } ?: boardConfig.language,
            screenBrightness = written.screenBrightness
                .takeUnless { it == ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED }
                ?: boardConfig.screenBrightness,
            screenOffDelay = written.screenOffDelay
                .takeUnless { it == 0U } ?: boardConfig.screenOffDelay,
            displayWindow = written.displayWindow ?: boardConfig.displayWindow,
        )
        // A board holds as many slots as it reports, and the protocol carries no more than four.
        boardProfiles = toProtocolProfiles(clamped, boardProfiles)
            .let { profiles -> profiles.copy(profiles = profiles.profiles.take(PROFILE_SLOTS)) }

        val boardNow = readBoard()
        // The read-back adopts every DeviceConfig setting, as the engine does. The active profile
        // is a slot, so it maps back to the id of the profile in that slot.
        val activeSlot = boardNow.knownProfiles.indexOfFirst { it.id == boardNow.activeProfileId }
        // Each profile is now in the slot of its list index, as the engine reports after a write.
        val stored = clamped.withProfilesInWrittenSlots().copy(
            activeProfileId = clamped.knownProfiles.getOrNull(activeSlot)?.id,
            bootText = boardNow.bootText,
            language = boardNow.language,
            screenBrightness = boardNow.screenBrightness,
            screenOffDelay = boardNow.screenOffDelay,
            displayWindow = boardNow.displayWindow,
        )
        log.d { "Fake board stored the boot text ${boardConfig.bootText}" }
        deviceWriteResultBus.emit(
            DeviceWriteResult(stored, error = null, requestedDevice = device, requestId = requestId)
        )
    }

    /** Decodes the board through the same mapper the real read path uses. */
    private fun readBoard(): Device = toDomainDevice(
        protoDevice = BOARD_DEVICE,
        protoDeviceConfig = boardConfig,
        protoProfiles = boardProfiles,
        // The real read path names a device after the peripheral, so the fake does the same.
        deviceName = BOARD_ADVERTISED_NAME,
        lastSeenDate = getCurrentLocalDate(),
    ).copy(advertisementData = FAKE_ADVERTISEMENT) // The engine attaches the advertisement the same way.

    private fun advertisedState(): BluetoothDeviceState = BluetoothDeviceState(
        devices = mapOf(FAKE_DEVICE_ID to FakePeripheral),
        advertisementData = mapOf(FAKE_DEVICE_ID to FAKE_ADVERTISEMENT),
    )

    private companion object {
        private const val PROFILE_SLOTS = 4

        private const val SCAN_DELAY_MILLIS = 600L
        private const val STEP_DELAY_MILLIS = 250L
        private const val WRITE_DELAY_MILLIS = 400L

        private val log = Arbor.tag("FakeBoard")
    }
}

/**
 * Scan key of the simulated board, which is also its board `device_id`.
 *
 * The seams key a peripheral by `peripheral.uuid.toDeviceUuid().toString()`, and
 * `String.toDeviceUuid()` returns a UUID string unchanged, so one valid UUID serves as the scan key,
 * as the peripheral id, and as the domain [Device.id]. A real board has a scan key that is not its
 * `device_id` (a MAC hash on Android), and the engine maps one to the other after the read. The fake
 * answers the requests itself, so it keeps the two equal and needs no map.
 */
private const val FAKE_DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"

/**
 * The 0xFFE0 service data of the fake, in the form that the firmware sends: the board type, then the
 * 4 version bytes `[year - 2000, week, count, flags]` (#129). The parser makes the version text.
 */
private val FAKE_ADVERTISEMENT: AdvertisementData =
    checkNotNull(AdvertisementData.parse(byteArrayOf(4, *"FAKE".encodeToByteArray(), 4, 26, 29, 0, 0x01)))

/** The read-only facts the board reports about itself. */
private val BOARD_DEVICE = snapshotpb.v1.Device(
    deviceId = Uuid.parse(FAKE_DEVICE_ID).toByteArray(),
    lockoutEnabled = false,
    model = BoardModel.BOARD_MODEL_AUTOCOCKER,
    supportedProfileCount = 4U,
)

/**
 * The peripheral the device browser sees. BlueFalcon declares `BluetoothPeripheral` as an interface,
 * so the fake needs no radio, no GATT and no services: nothing below the request bus reads them
 * while the simulated board answers.
 */
private object FakePeripheral : BluetoothPeripheral {
    override val uuid: String = FAKE_DEVICE_ID
    override val name: String = BOARD_ADVERTISED_NAME

    /** The service UUID that a real board advertises, so discovery finds the fake the same way (#128). */
    override val advertisedServiceUUIDs: List<Uuid> = listOf(SnapshotAdvertisement.FilterService)
    override val rssi: Float = -55f
    override val mtuSize: Int = 247
    override val services: List<BluetoothService> = emptyList()
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
}

/**
 * The configuration the board starts with, as a domain device that the mappers then encode. Two
 * profiles sit in the board's first two slots, with ids derived from the device id and the slot, the
 * way the mapper derives them on every read.
 */
private fun fakeBoardSeed(): Device {
    val deviceUuid = Uuid.parse(FAKE_DEVICE_ID)
    val profiles = listOf(
        MarkerProfile(
            id = getProfileUuid(deviceUuid, 0),
            name = "Semi",
            fireMode = MarkerFireMode.SEMI,
        ),
        MarkerProfile(
            id = getProfileUuid(deviceUuid, 1),
            name = "Ramp",
            fireMode = MarkerFireMode.RAMPING_NXL_2026,
            eyeMode = MarkerEyeMode.REFLECTIVE,
        ),
    )
    return Device(
        id = deviceUuid,
        name = BOARD_ADVERTISED_NAME,
        lastSeen = getCurrentLocalDate(),
        knownProfiles = profiles,
        bootText = "FAKE BOARD",
        activeProfileId = profiles.first().id,
        language = BoardLanguage.EN,
        screenBrightness = BoardScreenBrightness.DEFAULT,
        screenOffDelay = 30,
        displayWindow = DisplayWindow.FULL_PANEL,
    )
}
