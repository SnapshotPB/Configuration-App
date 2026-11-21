package com.snapshotpb.configuration.demo

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.bluetooth.ots.getCurrentLocalDate
import com.snapshotpb.configuration.common.screen.device.BOARD_ADVERTISED_NAME
import com.snapshotpb.configuration.model.BoardBluetoothAtBoot
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DiscoveredDevice
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.withProfilesInWrittenSlots
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The demo board of a debug watch build. It answers the connect, disconnect and write requests
 * for [DEMO_DEVICE_ID] only, and leaves every other request to the real radio, so a real board
 * near the watch still works while the demo board is on.
 *
 * The board walks the same four connect steps as the real engine and puts its device on the read
 * bus, so the sync delegate treats it as a real board. A write reports success with the clamped
 * device, which is what the real engine reports after a commit. While the board is connected and
 * has at least one eye watcher, it records a simulated eye level at 10 Hz.
 *
 * THREADING: [handle] gives the slow work to [scope] and returns at once, because the request bus
 * has no buffer. Everything here, also the eye simulation inside [connectionJob], runs on the main
 * thread.
 */
internal class WearFakeBoard(
    private val bluetoothStateManager: BluetoothStateManager,
    private val deviceReadResultBus: DeviceReadResultBus,
    private val deviceWriteResultBus: DeviceWriteResultBus,
) : WearDemoBoard {

    @Volatile
    private var enabled: Boolean = false

    override val active: Boolean get() = enabled

    /** The board is a process singleton, so its scope needs no owner to cancel it. */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var connectionJob: Job? = null

    override fun applySwitch(on: Boolean) {
        if (on == enabled) return
        enabled = on
        log.d { "Wear demo board switch is now $enabled" }
        if (!on) {
            connectionJob?.cancel()
            connectionJob = null
        }
    }

    override suspend fun handle(request: BluetoothRequest): Boolean {
        if (!enabled) return false
        return when (request) {
            is BluetoothRequest.Connect -> {
                if (request.deviceId != DEMO_DEVICE_ID) return false
                log.d { "Wear demo board answers $request" }
                // One attempt at a time, the rule the real engine keeps.
                connectionJob?.cancel()
                connectionJob = scope.launch { connect() }
                true
            }

            is BluetoothRequest.Disconnect -> {
                if (request.deviceId != DEMO_DEVICE_ID) return false
                log.d { "Wear demo board answers $request" }
                connectionJob?.cancel()
                connectionJob = null
                bluetoothStateManager.deviceDisconnected(DEMO_DEVICE_ID)
                bluetoothStateManager.resetProgress()
                true
            }

            is BluetoothRequest.WriteDevice -> {
                if (request.deviceId != DEMO_DEVICE_ID) return false
                log.d { "Wear demo board answers $request" }
                val device = request.device
                val requestId = request.requestId
                scope.launch { write(device, requestId) }
                true
            }

            else -> false
        }
    }

    override fun withDemoDevice(real: List<DiscoveredDevice>, scanning: Boolean): List<DiscoveredDevice> {
        if (!enabled || !scanning || real.any { it.id == DEMO_DEVICE_ID }) return real
        return real + DEMO_DISCOVERED_DEVICE
    }

    private suspend fun connect() {
        bluetoothStateManager.resetProgress()
        bluetoothStateManager.updateProgress(ConnectionProgress(step1 = StepStatus.IN_PROGRESS))
        delay(STEP_DELAY_MILLIS)
        bluetoothStateManager.updateProgress(
            ConnectionProgress(step1 = StepStatus.COMPLETED, step2 = StepStatus.IN_PROGRESS)
        )
        delay(STEP_DELAY_MILLIS)
        bluetoothStateManager.deviceConnected(DEMO_DEVICE_ID)
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
        deviceReadResultBus.emit(DeviceReadResult(demoDevice()))
        simulateEye()
    }

    /**
     * Records a simulated [EyeSensorState.ACTIVE] level while the board is connected and has at
     * least one eye watcher.
     *
     * At zero watchers, or when [connectionJob] stops, this function clears the eye state of
     * [DEMO_DEVICE_ID]. Thus a new watcher sees [EyeReadingState.Waiting], not an old level.
     */
    private suspend fun simulateEye() {
        try {
            combine(
                bluetoothStateManager.connectedDevices.map { DEMO_DEVICE_ID in it },
                bluetoothStateManager.eyeWatcherCount(DEMO_DEVICE_ID).map { it > 0 },
            ) { connected, watched -> connected && watched }
                .distinctUntilChanged()
                .collectLatest { simulate ->
                    if (!simulate) {
                        bluetoothStateManager.clearEyeReading(DEMO_DEVICE_ID)
                        return@collectLatest
                    }
                    var tick = 0
                    while (true) {
                        bluetoothStateManager.setEyeReading(
                            DEMO_DEVICE_ID,
                            EyeReadingState.Reading(
                                EyeReading(
                                    levelRaw = simulatedEyeLevelRaw(tick),
                                    fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
                                    state = EyeSensorState.ACTIVE,
                                ),
                            ),
                        )
                        tick++
                        delay(EYE_TICK_MILLIS)
                    }
                }
        } finally {
            bluetoothStateManager.clearEyeReading(DEMO_DEVICE_ID)
        }
    }

    private suspend fun write(device: Device, requestId: Long) {
        delay(WRITE_DELAY_MILLIS)
        deviceWriteResultBus.emit(
            DeviceWriteResult(
                // Each profile is now in the slot of its list index, as the engine reports.
                device.clampedToFirmwareRanges().withProfilesInWrittenSlots(),
                error = null,
                requestedDevice = device,
                requestId = requestId,
            )
        )
    }

    private fun demoDevice(): Device = Device(
        id = Uuid.parse(DEMO_DEVICE_ID),
        name = "Snapshot PB E2E",
        lastSeen = getCurrentLocalDate(),
        knownProfiles = listOf(
            // A board read gives each profile the slot that it came from.
            MarkerProfile(
                id = PROFILE_1_ID,
                name = "Semi Auto",
                fireMode = MarkerFireMode.SEMI,
                sourceSlot = 0,
            ),
            MarkerProfile(
                id = PROFILE_2_ID,
                name = "Ramp",
                fireMode = MarkerFireMode.RAMPING_NXL_2026,
                sourceSlot = 1,
            ),
        ),
        activeProfileId = PROFILE_1_ID,
        bootText = "SnapshotPB Mock",
        boltMode = MarkerBoltMode.CLOSED,
        bluetoothAtBoot = BoardBluetoothAtBoot.OFF,
        lastModified = System.currentTimeMillis(),
    )

    private companion object {
        private const val STEP_DELAY_MILLIS = 500L
        private const val WRITE_DELAY_MILLIS = 400L

        private val PROFILE_1_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
        private val PROFILE_2_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")

        private val log = Arbor.tag("WearFakeBoard")
    }
}

/**
 * Id of the demo board. It is a valid UUID, so it serves as the scan key, the peripheral id and
 * the domain [Device.id]. The E2E tests use the same value.
 */
private const val DEMO_DEVICE_ID = "00000000-0000-0000-0000-000000001234"

private val DEMO_DISCOVERED_DEVICE = DiscoveredDevice(
    id = DEMO_DEVICE_ID,
    name = BOARD_ADVERTISED_NAME,
    boardType = "Cocker",
    fwVersion = null,
)

/** Time between two simulated eye samples (10 Hz, the rate of the board). */
private const val EYE_TICK_MILLIS = 100L

/** Lowest raw ADC level of the simulated eye wave. */
private const val EYE_LEVEL_LOW_RAW = 600

/** Highest raw ADC level of the simulated eye wave. */
private const val EYE_LEVEL_HIGH_RAW = 3500

/** Number of ticks in one full cycle of the simulated eye wave (4 s at 10 Hz). */
private const val EYE_PERIOD_TICKS = 40

/** Number of ticks in one half cycle of the simulated eye wave. */
private const val EYE_HALF_PERIOD_TICKS = EYE_PERIOD_TICKS / 2

/**
 * Gives the simulated raw eye level for [tick] on a triangle wave from [EYE_LEVEL_LOW_RAW] to
 * [EYE_LEVEL_HIGH_RAW].
 *
 * One period is [EYE_PERIOD_TICKS] ticks (4 s at 10 Hz). The wave goes up for the first half and
 * down for the second half, and passes the default threshold (2047) in each direction. Negative
 * ticks wrap with [Int.mod].
 */
internal fun simulatedEyeLevelRaw(tick: Int): Int {
    val phase = tick.mod(EYE_PERIOD_TICKS)
    val step = if (phase <= EYE_HALF_PERIOD_TICKS) phase else EYE_PERIOD_TICKS - phase
    return EYE_LEVEL_LOW_RAW + (EYE_LEVEL_HIGH_RAW - EYE_LEVEL_LOW_RAW) * step / EYE_HALF_PERIOD_TICKS
}
