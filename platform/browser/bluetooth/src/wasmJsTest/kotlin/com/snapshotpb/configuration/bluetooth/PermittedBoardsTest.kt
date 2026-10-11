@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.engine.js.JsEngine
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Tests of [PermittedBoards] with the real BlueFalcon `JsEngine` on a fake `navigator.bluetooth`.
 * As in Chrome, `requestDevice` is on the prototype of the fake, and it is the chooser: each test
 * counts its calls, and a restore must not call it. A connect then goes to the GATT server of the
 * permitted device.
 */
class PermittedBoardsTest {

    @AfterTest
    fun removeFakeBluetooth() = removeNavigatorBluetooth()

    @Test
    fun restore_withoutWatch_registersTheDeviceWithNoChooser() = runTest {
        val device = fakeDevice(BOARD_ID, WATCH_NONE)
        val bluetooth = installFakeBluetooth(jsArrayOf(device))
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()

        val peripheral = blueFalcon.peripherals.value.single()
        assertEquals(BOARD_ID, peripheral.uuid)
        assertEquals(0, chooserCalls(bluetooth))
        assertFalse(hasOwnRequestDevice(bluetooth), "the one-time offer must go away")

        blueFalcon.connect(peripheral)
        assertEquals(BluetoothPeripheralState.Connected, blueFalcon.connectionState(peripheral))
        assertTrue(gattConnected(device))
    }

    @Test
    fun restore_registersEachPermittedDevice() = runTest {
        val bluetooth = installFakeBluetooth(
            jsArrayOf(fakeDevice(BOARD_ID, WATCH_NONE), fakeDevice(OTHER_BOARD_ID, WATCH_NONE))
        )
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()

        assertEquals(setOf(BOARD_ID, OTHER_BOARD_ID), blueFalcon.peripherals.value.map { it.uuid }.toSet())
        assertEquals(0, chooserCalls(bluetooth))
        assertFalse(hasOwnRequestDevice(bluetooth))
    }

    @Test
    fun restore_withWatch_registersTheDeviceOnlyWhenItIsInRange() = runTest {
        val device = fakeDevice(BOARD_ID, WATCH_OK)
        val bluetooth = installFakeBluetooth(jsArrayOf(device))
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()
        assertTrue(blueFalcon.peripherals.value.isEmpty(), "no advertisement came yet")

        advertise(device)
        val peripheral = blueFalcon.peripherals.first { it.isNotEmpty() }.single()

        assertEquals(BOARD_ID, peripheral.uuid)
        assertTrue(watchStopped(device), "the watch must stop after the first advertisement")
        assertEquals(0, chooserCalls(bluetooth))
        assertFalse(hasOwnRequestDevice(bluetooth))
    }

    @Test
    fun restore_whenTheBrowserRefusesTheWatch_registersTheDeviceImmediately() = runTest {
        val bluetooth = installFakeBluetooth(jsArrayOf(fakeDevice(BOARD_ID, WATCH_REFUSED)))
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()

        assertEquals(BOARD_ID, blueFalcon.peripherals.value.single().uuid)
        assertEquals(0, chooserCalls(bluetooth))
        assertFalse(hasOwnRequestDevice(bluetooth))
    }

    @Test
    fun restore_withoutGetDevices_doesNothing() = runTest {
        val bluetooth = installFakeBluetooth(null)
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()

        assertTrue(blueFalcon.peripherals.value.isEmpty())
        assertEquals(0, chooserCalls(bluetooth))
    }

    @Test
    fun restore_whenGetDevicesFails_doesNothing() = runTest {
        val bluetooth = installFailingBluetooth()
        val blueFalcon = BlueFalcon(JsEngine())

        PermittedBoards(blueFalcon, emptyList(), backgroundScope).restore()

        assertTrue(blueFalcon.peripherals.value.isEmpty())
        assertEquals(0, chooserCalls(bluetooth))
    }

    @Test
    fun offer_givesTheDeviceOnlyToACallWithTheMarker() = runTest {
        val device = fakeDevice(BOARD_ID, WATCH_NONE)
        val bluetooth = installFakeBluetooth(jsArrayOf(device))
        val marker = PermittedBoards.OFFER_MARKER.toString()
        val withdraw = assertNotNull(offerOnNextRequestOrNull(bluetooth, device, marker))

        // A chooser scan of the user has no marker, so it gets the real chooser, and the offer stays.
        requestDeviceOrNull(bluetooth, OTHER_SERVICE).await<JsAny?>()
        assertEquals(1, chooserCalls(bluetooth))
        assertTrue(hasOwnRequestDevice(bluetooth))

        val given = requestDeviceOrNull(bluetooth, marker).await<JsAny?>()
        assertTrue(given === device)
        assertFalse(hasOwnRequestDevice(bluetooth), "the offer must go after its use")
        assertEquals(1, chooserCalls(bluetooth))

        callWithdraw(withdraw)
        assertFalse(hasOwnRequestDevice(bluetooth))
    }

    @Test
    fun manager_putsThePermittedBoardInTheDeviceState() = runTest {
        val bluetooth = installFakeBluetooth(jsArrayOf(fakeDevice(BOARD_ID, WATCH_NONE)))
        val manager = newManager(restorePermittedBoards = true)

        val devices = manager.deviceState.first { it.devices.isNotEmpty() }.devices

        // The connect finds the board in the device state, so it does not scan (see ensurePeripheral).
        assertEquals(setOf(BOARD_ID.toDeviceUuid().toString()), devices.keys)
        assertEquals(1, getDevicesCalls(bluetooth))
        assertEquals(0, chooserCalls(bluetooth))
    }

    @Test
    fun manager_withoutTheRestore_doesNotAskForPermittedDevices() = runTest {
        val bluetooth = installFakeBluetooth(jsArrayOf(fakeDevice(BOARD_ID, WATCH_NONE)))
        val manager = newManager(restorePermittedBoards = false)
        testScheduler.advanceUntilIdle()

        assertTrue(manager.deviceState.value.devices.isEmpty())
        assertEquals(0, getDevicesCalls(bluetooth))
    }

    private fun TestScope.newManager(restorePermittedBoards: Boolean) =
        BluetoothManager(
            blueFalcon = BlueFalcon(JsEngine()),
            bluetoothStateManager = BluetoothStateManager(),
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            restorePermittedBoards = restorePermittedBoards,
            coroutineScope = backgroundScope,
        )

    private companion object {
        const val BOARD_ID = "cGVybWl0dGVkLWJvYXJkPT0="
        const val OTHER_BOARD_ID = "b3RoZXItYm9hcmQ9PQ=="
        const val OTHER_SERVICE = "00001825-0000-1000-8000-00805f9b34fb"
        const val WATCH_NONE = "none"
        const val WATCH_OK = "ok"
        const val WATCH_REFUSED = "refused"
    }
}

// ---- fakes of the Web Bluetooth objects ----

/**
 * A permitted `BluetoothDevice` with a GATT server. [watch] is "none" (no `watchAdvertisements()`),
 * "ok" (the watch starts) or "refused" (the watch rejects).
 */
private fun fakeDevice(id: String, watch: String): PermittedDeviceJs = js(
    """(() => {
        const device = {
            id: id,
            name: 'Snapshot PB',
            listeners: [],
            signal: null,
            gatt: {
                connected: false,
                connect() { this.connected = true; return Promise.resolve(this); },
                disconnect() { this.connected = false; },
            },
            addEventListener(type, listener) {
                if (type === 'advertisementreceived') device.listeners.push(listener);
            },
            removeEventListener(type, listener) {
                device.listeners = device.listeners.filter((l) => l !== listener);
            },
        };
        if (watch === 'ok') {
            device.watchAdvertisements = (options) => {
                device.signal = options && options.signal;
                return Promise.resolve();
            };
        } else if (watch === 'refused') {
            device.watchAdvertisements = () => Promise.reject(new DOMException('refused', 'NotSupportedError'));
        }
        return device;
    })()"""
)

private fun advertise(device: JsAny): Unit =
    js("device.listeners.slice().forEach((listener) => listener({ device: device, rssi: -60 }))")

private fun watchStopped(device: JsAny): Boolean = js("!!(device.signal && device.signal.aborted)")

private fun gattConnected(device: JsAny): Boolean = js("device.gatt.connected")

/**
 * Puts a fake `navigator.bluetooth` in place. As in Chrome, `requestDevice` and `getDevices` are on
 * its prototype. A null [devices] gives a browser without `getDevices()`.
 */
private fun installFakeBluetooth(devices: JsArray<PermittedDeviceJs>?): JsAny = js(
    """(() => {
        const counts = { chooserCalls: 0, getDevicesCalls: 0 };
        const prototype = {
            requestDevice(options) {
                counts.chooserCalls++;
                return Promise.reject(new DOMException('no chooser in a test', 'NotFoundError'));
            },
        };
        if (devices) {
            prototype.getDevices = function () { counts.getDevicesCalls++; return Promise.resolve(devices); };
        }
        const bluetooth = Object.create(prototype);
        Object.defineProperty(bluetooth, 'counts', { value: counts });
        Object.defineProperty(navigator, 'bluetooth', { value: bluetooth, configurable: true, writable: true });
        return bluetooth;
    })()"""
)

private fun installFailingBluetooth(): JsAny = js(
    """(() => {
        const counts = { chooserCalls: 0, getDevicesCalls: 0 };
        const prototype = {
            requestDevice() { counts.chooserCalls++; return Promise.reject(new Error('chooser')); },
            getDevices() { return Promise.reject(new DOMException('the flag is off', 'NotSupportedError')); },
        };
        const bluetooth = Object.create(prototype);
        Object.defineProperty(bluetooth, 'counts', { value: counts });
        Object.defineProperty(navigator, 'bluetooth', { value: bluetooth, configurable: true, writable: true });
        return bluetooth;
    })()"""
)

private fun removeNavigatorBluetooth(): Unit = js("{ delete navigator.bluetooth; }")

private fun chooserCalls(bluetooth: JsAny): Int = js("bluetooth.counts.chooserCalls")

private fun getDevicesCalls(bluetooth: JsAny): Int = js("bluetooth.counts.getDevicesCalls")

private fun hasOwnRequestDevice(bluetooth: JsAny): Boolean =
    js("Object.prototype.hasOwnProperty.call(bluetooth, 'requestDevice')")

/** Calls `requestDevice` as the engine does, with [service] in `optionalServices`. A rejection gives null. */
private fun requestDeviceOrNull(bluetooth: JsAny, service: String): Promise<JsAny?> =
    js("bluetooth.requestDevice({ acceptAllDevices: true, optionalServices: [service] }).catch(() => null)")

private fun callWithdraw(withdraw: JsAny): Unit = js("withdraw()")

private fun jsArrayOf(vararg items: PermittedDeviceJs): JsArray<PermittedDeviceJs> {
    val array = JsArray<PermittedDeviceJs>()
    items.forEachIndexed { index, item -> array[index] = item }
    return array
}
