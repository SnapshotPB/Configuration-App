package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.emulator.EmulatorEngine
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.js.JsEngine
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Binds the Web Bluetooth-backed [BluetoothManager], mirroring how the iOS/desktop platform
 * modules inject their engine. As of blue-falcon 3.4.5 the upstream [JsEngine] ships a real
 * `wasm-js` variant that is OTS-complete (notifications wired, `optionalServices` populated from
 * the scan filters, every GATT op awaited), so there is no custom engine to write for Web Bluetooth.
 *
 * In the 3D viewer of the board, the engine is [EmulatorEngine] in place of [JsEngine]. It talks to
 * the WebAssembly model of the board through the bridge of the viewer, and it copies [JsEngine].
 * Only [JsEngine] gets the boards that the user permitted in an earlier visit.
 */
internal val bluetoothModule = DI.Module("bluetoothModule") {
    bindSingleton {
        val emulatorEngine = EmulatorEngine.fromViewerOrNull()
        BluetoothManager(
            blueFalcon = BlueFalcon(engine = emulatorEngine ?: JsEngine()),
            bluetoothStateManager = instance(),
            bleDebugLog = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance(),
            restorePermittedBoards = emulatorEngine == null
        )
    }
}