package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform-specific surface of the Bluetooth stack. [BluetoothConnectionEngine] — the shared,
 * `final` connect/teardown/link-loss-monitor/OTS pipeline — is constructed with one of these and
 * calls into it for the handful of operations that genuinely differ per platform: scanning, device
 * bookkeeping, manager-state reconciliation, connect-readiness, MTU negotiation, debug formatting.
 *
 * This is the composition seam that replaced the old `AbstractBluetoothManager` open base. Because
 * the engine receives a *fully constructed* seams object instead of inheriting from it, the engine
 * can launch its BlueFalcon collectors from its own `init` with no leaked-`this` hazard — there is
 * no "subclasses must call observeBluetooth() last in their init" contract to forget.
 *
 * [DefaultBluetoothSeams] supplies the desktop/iOS/browser-shared behavior; platforms subclass it to
 * override only what they need. A seam may carry its own state (e.g. Android's permission flags +
 * native scan) and owns [deviceState]; the engine treats [deviceState] as read-only.
 */
interface PlatformBluetoothSeams {
    /** Discovered-device state: written by the seam (scan + advertisement), read by the engine, observed by the UI. */
    val deviceState: StateFlow<BluetoothDeviceState>

    /** Records a peripheral surfaced by the BlueFalcon scan into [deviceState]. */
    fun addDevice(peripheral: BluetoothPeripheral)

    /** Reconciles [BluetoothState] with a BlueFalcon manager-state change (pushed via the BluetoothStateManager). */
    fun onManagerState(state: BluetoothManagerState)

    /** Issues / cancels the platform scan for boards (see [SnapshotAdvertisement.scanFilters]). */
    suspend fun scanForDevices()
    suspend fun stopScanForDevices()

    /**
     * Gate run after the attempt enters [BluetoothState.Connecting], before any radio work. Returning
     * false aborts the connect; a seam that aborts is responsible for updating state itself.
     */
    suspend fun awaitReadyToConnect(deviceId: String): Boolean

    /**
     * Hook after services are discovered, before OTS init. Android negotiates a larger MTU here. The
     * other seams make sure that the OTS service has its characteristics, because some engines
     * supply them only after a separate `discoverCharacteristics`.
     */
    suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral)

    /**
     * Waits for service discovery to complete; returns whether the peripheral exposed any services.
     * `BlueFalcon.connect()` can return before the link opens, so an implementation must not send a
     * GATT request until the peripheral reports `Connected`.
     */
    suspend fun awaitServicesDiscovered(peripheral: BluetoothPeripheral): Boolean

    /**
     * Waits after a notification or indication subscription on [characteristic] until the stack has
     * written its CCC to the board. A control-point write sent before that write completes gets no
     * indication, so the OTS client calls this after each subscription. How long that takes, and
     * whether the stack reports it, differs per platform.
     */
    suspend fun awaitSubscribed(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic)

    /** Renders a captured debug event for the log line (hex by default; Android logs ASCII text). */
    fun formatDebugBytes(event: BleDebugEvent): String
}