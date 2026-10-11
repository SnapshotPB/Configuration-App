@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.bluetooth

import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.ServiceFilter
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Arbor.tag("PermittedBoards")

// ---- Web Bluetooth permitted devices (Kotlin/Wasm external declarations) ----

/** A `BluetoothDevice` that the user gave to this site in an earlier chooser. */
internal external interface PermittedDeviceJs : JsAny {
    val id: String
}

private fun navigatorBluetooth(): JsAny? =
    js("(typeof navigator !== 'undefined' && navigator.bluetooth) ? navigator.bluetooth : null")

/** The Promise of `getDevices()`, or null when the browser has no `getDevices()`. */
private fun permittedDevicesOrNull(bluetooth: JsAny): Promise<JsArray<PermittedDeviceJs>>? =
    js("(typeof bluetooth.getDevices === 'function') ? bluetooth.getDevices() : null")

/**
 * Starts `watchAdvertisements()` on [device], and calls [onAdvertisement] one time, for the first
 * advertisement. Then it stops the watch. The watch also stops after [limitMillis] with no
 * advertisement. Returns null when the device has no `watchAdvertisements()`. The Promise rejects
 * when the browser refuses the watch.
 */
private fun watchFirstAdvertisementOrNull(
    device: PermittedDeviceJs,
    limitMillis: Int,
    onAdvertisement: () -> Unit,
): Promise<JsAny?>? = js(
    """(() => {
        if (typeof device.watchAdvertisements !== 'function') return null;
        const abort = new AbortController();
        let timer = null;
        const listener = () => {
            clearTimeout(timer);
            device.removeEventListener('advertisementreceived', listener);
            abort.abort();
            onAdvertisement();
        };
        timer = setTimeout(() => {
            device.removeEventListener('advertisementreceived', listener);
            abort.abort();
        }, limitMillis);
        device.addEventListener('advertisementreceived', listener);
        return device.watchAdvertisements({ signal: abort.signal }).then(
            () => null,
            (e) => {
                if (e && e.name === 'AbortError') return null;
                clearTimeout(timer);
                device.removeEventListener('advertisementreceived', listener);
                throw e;
            }
        );
    })()"""
)

/**
 * Makes the next `bluetooth.requestDevice()` that has [marker] in its `optionalServices` give
 * [device] with no chooser, one time. The own property hides the method of `Bluetooth.prototype`.
 * A call without [marker] goes to the real method, so a chooser that the user opens is not
 * affected. Returns a function that removes the offer when no call used it, or null when the
 * browser refused the property.
 */
internal fun offerOnNextRequestOrNull(bluetooth: JsAny, device: PermittedDeviceJs, marker: String): JsAny? = js(
    """(() => {
        const hadOwn = Object.prototype.hasOwnProperty.call(bluetooth, 'requestDevice');
        const previous = bluetooth.requestDevice;
        const restore = () => {
            if (hadOwn) bluetooth.requestDevice = previous;
            else delete bluetooth.requestDevice;
        };
        const offer = (options) => {
            const services = (options && options.optionalServices) || [];
            if (!Array.prototype.includes.call(services, marker)) return previous.call(bluetooth, options);
            restore();
            return Promise.resolve(device);
        };
        try {
            bluetooth.requestDevice = offer;
        } catch (e) {
            return null;
        }
        if (bluetooth.requestDevice !== offer) return null;
        return () => { if (bluetooth.requestDevice === offer) restore(); };
    })()"""
)

private fun callFunction(callable: JsAny): Unit = js("callable()")

/**
 * Gives the Web Bluetooth engine the boards that the user permitted in an earlier visit. Thus a
 * connect after a page reload needs no chooser.
 *
 * `navigator.bluetooth.getDevices()` gives these devices. Some Chrome versions enable it only with
 * a flag, so each call is feature-detected. A browser without it shows the chooser, as before.
 *
 * BlueFalcon's `JsEngine` has no `getDevices()`, and it has no API to add a device: its peripheral
 * map is private, and the constructor of `JsBluetoothPeripheral` is internal. The engine adds a
 * device only in `scan()`, which calls `navigator.bluetooth.requestDevice()` and keeps the device
 * that the Promise gives. So [register] puts a one-time `requestDevice` on `navigator.bluetooth`
 * that gives the permitted device and no chooser, and then calls `scan()`. The engine then holds
 * the device as if the user selected it.
 *
 * The offer applies only to a call that has [OFFER_MARKER] in its `optionalServices`, and only
 * [register] adds that UUID to its scan. Thus a chooser scan of the user that starts at the same
 * time gets the real chooser, also if a BlueFalcon plugin makes the scan wait. The offer removes
 * itself on its first use, and [register] removes it when the scan did not use it. A [Mutex] makes
 * sure that only one offer exists at a time.
 *
 * Where the device has `watchAdvertisements()`, a device is registered only when an advertisement
 * shows that it is in range. The watch stops after [WATCH_LIMIT_MILLIS], so that a board that
 * stays off does not keep the radio busy. Without the watch, or when the browser refuses it, the
 * device is registered immediately, and a connect to a board out of range fails at step 2.
 *
 * Use this with `JsEngine` only. `EmulatorEngine` has no chooser and no permitted devices.
 */
internal class PermittedBoards(
    private val blueFalcon: BlueFalcon,
    private val filters: List<ServiceFilter>,
    private val scope: CoroutineScope,
) {
    private val registerMutex = Mutex()

    /** Finds the permitted devices and registers each one, now or when it is in range. */
    suspend fun restore() {
        val bluetooth = navigatorBluetooth() ?: return
        val pending = permittedDevicesOrNull(bluetooth)
        if (pending == null) {
            log.d { "This browser has no getDevices(). A connect needs the chooser." }
            return
        }
        val devices = try {
            pending.await<JsArray<PermittedDeviceJs>>().items()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w(error) { "getDevices() failed" }
            return
        }
        log.d { "Permitted devices: ${devices.size}" }
        // One child for each device, so a watch that does not start cannot stop the others.
        coroutineScope {
            devices.forEach { device -> launch { watchThenRegister(bluetooth, device) } }
        }
    }

    private suspend fun watchThenRegister(bluetooth: JsAny, device: PermittedDeviceJs) {
        val watch = watchFirstAdvertisementOrNull(device, WATCH_LIMIT_MILLIS) {
            log.d { "Permitted device ${device.id} is in range" }
            scope.launch { register(bluetooth, device) }
        }
        if (watch == null) {
            register(bluetooth, device)
            return
        }
        try {
            watch.await<JsAny?>()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w(error) { "watchAdvertisements() failed for ${device.id}. The device is registered now." }
            register(bluetooth, device)
        }
    }

    /** Adds [device] to the engine through a one-time `requestDevice` offer. */
    private suspend fun register(bluetooth: JsAny, device: PermittedDeviceJs) {
        registerMutex.withLock {
            val withdraw = offerOnNextRequestOrNull(bluetooth, device, OFFER_MARKER.toString())
            if (withdraw == null) {
                log.w { "The browser refused the requestDevice offer. ${device.id} needs the chooser." }
                return
            }
            try {
                blueFalcon.scan(filters + ServiceFilter(OFFER_MARKER))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                log.w(error) { "The registration of permitted device ${device.id} failed" }
            } finally {
                callFunction(withdraw)
            }
        }
    }

    internal companion object {
        /**
         * A UUID that no board has. Only a scan from [register] has it in `optionalServices`, and
         * such a scan never gets to the real `requestDevice`.
         */
        val OFFER_MARKER: Uuid = Uuid.parse("5350425f-7065-726d-6974-746564000001")

        /** Five minutes. */
        const val WATCH_LIMIT_MILLIS = 300_000
    }
}
