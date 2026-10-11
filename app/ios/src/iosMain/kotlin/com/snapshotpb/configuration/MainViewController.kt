package com.snapshotpb.configuration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.snapshotpb.configuration.bluetooth.BluetoothAuthorization
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.currentBluetoothAuthorization
import com.snapshotpb.configuration.common.App
import com.snapshotpb.configuration.debug.DebugBoard
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.media.MediaState
import com.snapshotpb.configuration.media.MediaStateManager
import com.snapshotpb.configuration.screen.screenModule
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.Seedling
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.compose.withDI
import org.kodein.di.direct
import org.kodein.di.instance
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController

private val log = Arbor.tag("MainViewController")

/**
 * Root DI graph for the iOS app, assembled exactly like the desktop entry point:
 * the shared [appModule] (which imports [com.snapshotpb.configuration.di.coreDiModule]),
 * the iOS [platformModule] (BlueFalcon-backed bluetooth) from `platform:ios:di`, and the
 * iOS [screenModule] holding the platform-specific screen ViewModels.
 */
private val di = DI {
    import(appModule)
    import(platformModule)
    import(screenModule)
}

private val appScope = CoroutineScope(Dispatchers.Default)

private var started = false

/**
 * Resolved lazily so CoreBluetooth — and the iOS Bluetooth permission prompt — only starts when
 * needed: on the first user-initiated request (see [onBluetoothRequest]), or at launch only when
 * Bluetooth is already authorized (see [startOnce]). It is never resolved speculatively while
 * permission is still undetermined, so the prompt can't appear at launch.
 */
private val bluetoothManager: BluetoothManager by lazy { di.direct.instance() }

/**
 * The simulated-board seam. Resolving it starts no radio: a release framework binds an inert
 * implementation, and the debug one only answers requests once the launch argument turns it on.
 */
private val debugBoard: DebugBoard by lazy { di.direct.instance() }

/**
 * The launch argument that turns the simulated board on, the iOS counterpart of the Demo launcher
 * entry on Android. iOS has no second app icon to tap, so the switch rides the launch instead —
 * `XCUIApplication.launchArguments` for a UI test, or the scheme's arguments for a hand demo.
 *
 * The iOS Simulator has no CoreBluetooth at all, so this is what lets the app be driven there.
 */
private const val FAKE_BOARD_ARGUMENT = "--fake-board"

private fun fakeBoardSwitch(): Boolean =
    NSProcessInfo.processInfo.arguments.contains(FAKE_BOARD_ARGUMENT)

/**
 * One-time process bootstrap, mirroring the desktop [BaseApplication]:
 * seed Arbor logging, mark media Ready (no media/permission flow on iOS yet), and — critically —
 * bridge the [BluetoothRequestManager] bus to the [BluetoothManager]. The shared ViewModels only
 * ever `send` onto the bus, which is a zero-buffer [kotlinx.coroutines.flow.SharedFlow]; without a
 * collector every scan/connect/disconnect/write would suspend forever. Safe to call repeatedly.
 */
private fun startOnce() {
    if (started) return
    started = true
    Arbor.sow(Seedling())

    val mediaStateManager: MediaStateManager = di.direct.instance()
    mediaStateManager.update(MediaState.Ready)

    // Apply the simulated-board switch before anything reports the real radio: an active board owns
    // the Bluetooth state from here on.
    debugBoard.applySwitch(fakeBoardSwitch())

    // Bridge the request bus to the manager, but do NOT resolve [bluetoothManager] here: constructing
    // it starts BlueFalcon's CBCentralManager, which is what makes iOS show the Bluetooth permission
    // prompt. That is deferred to the first user-initiated request (see [onBluetoothRequest]) so the
    // prompt never appears at launch.
    val bluetoothRequestManager: BluetoothRequestManager = di.direct.instance()
    appScope.launch {
        bluetoothRequestManager.bus.collect { request ->
            log.d { "BluetoothRequestManager bus request: $request" }
            // Isolate per-request failures: an uncaught throw here would cancel the collector and
            // permanently kill the request bridge (every later scan/connect/write would silently
            // no-op). writeDeviceConfiguration throws when not connected, and BlueFalcon calls can
            // throw, so swallow-and-log to keep the bus alive.
            runCatching { onBluetoothRequest(request) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) { "Handling Bluetooth request $request failed" }
                }
        }
    }

    // Seed Bluetooth state from the current authorization now, and re-seed every time the app returns
    // to the foreground. The foreground re-check is what makes denial and Settings changes recover:
    // BlueFalcon collapses "denied" and "off" into the same NotReady and a StateFlow conflates equal
    // values, so answering the permission prompt (the alert resigns/re-activates the app) or toggling
    // permission in Settings and returning produces no managerState emission to react to — re-reading
    // authorization here does.
    seedBluetoothState()
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
        usingBlock = { seedBluetoothState() },
    )

    log.d { "iOS application started" }
}

/**
 * Brings [BluetoothState] in line with the OS Bluetooth authorization: activate CoreBluetooth when
 * already authorized (no prompt — its collector then settles to Ready), surface [BluetoothState.
 * PermissionDenied] when denied, and leave the default NeedsPermission untouched when undetermined
 * (so the prompt stays deferred to the user's grant tap). Safe to call repeatedly — [BluetoothManager.
 * activate] is idempotent and the denied branch only re-asserts an already-correct state.
 */
private fun seedBluetoothState() {
    // An active simulated board owns the Bluetooth state, so leave it alone — the same skip the
    // Android activity makes before it reports the real radio.
    if (debugBoard.active) return
    when (currentBluetoothAuthorization()) {
        BluetoothAuthorization.AUTHORIZED -> bluetoothManager.activate()
        BluetoothAuthorization.DENIED ->
            di.direct.instance<BluetoothStateManager>().update(BluetoothState.PermissionDenied)
        BluetoothAuthorization.NOT_DETERMINED -> Unit
    }
}

/**
 * Routes a [BluetoothRequest] from the bus to the [BluetoothManager], mirroring the desktop
 * `Application.onBluetoothRequest`. EnableBluetooth is a no-op that deliberately leaves the manager
 * uncreated (the user enables Bluetooth in Settings/Control Center). RequestPermission depends on the
 * current authorization: when not-yet-determined it activates the manager — creating CoreBluetooth's
 * CBCentralManager, which is the moment iOS shows the permission prompt — and when already denied it
 * deep-links to Settings instead, since iOS will not prompt again.
 */
private suspend fun onBluetoothRequest(request: BluetoothRequest) {
    // The simulated board claims the requests it answers; a false return leaves them to the radio.
    if (debugBoard.handle(request)) return
    when (request) {
        BluetoothRequest.EnableBluetooth ->
            log.d { "Enable Bluetooth requested on iOS; the user must enable it in Settings/Control Center." }
        BluetoothRequest.EnableLocation ->
            log.d { "Enable Location requested on iOS; not applicable — iOS BLE scanning does not depend on a Location toggle." }
        BluetoothRequest.RequestPermission -> when (currentBluetoothAuthorization()) {
            BluetoothAuthorization.DENIED -> openAppSettings()
            else -> bluetoothManager.activate()
        }
        BluetoothRequest.StartScan -> bluetoothManager.startScanning()
        BluetoothRequest.StopScan -> bluetoothManager.stopScanning()
        is BluetoothRequest.Connect -> bluetoothManager.connect(request.deviceId)
        is BluetoothRequest.Disconnect -> bluetoothManager.disconnect(request.deviceId)
        is BluetoothRequest.WriteDevice ->
            bluetoothManager.writeDeviceConfiguration(request.deviceId, request.device, request.requestId)
    }
}

/**
 * Opens this app's page in the iOS Settings app, where the user can re-grant Bluetooth — the only
 * recovery once permission has been denied. Must run on the main thread.
 */
private suspend fun openAppSettings() {
    withContext(Dispatchers.Main) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: run {
            log.e { "Unable to build the iOS app settings URL" }
            return@withContext
        }
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

/**
 * Entry point invoked from Swift to host the shared Compose [App] inside a [UIViewController].
 *
 * The SwiftUI host ignores the safe area, so the Compose view fills the full screen. The
 * [safeDrawingPadding] keeps the content clear of the notch, the Dynamic Island, the home
 * indicator and the keyboard, as `MainActivity` does on Android.
 */
fun MainViewController(): UIViewController {
    startOnce()
    return ComposeUIViewController {
        withDI(di) {
            AppTheme {
                Box(Modifier.safeDrawingPadding()) {
                    App()
                }
            }
        }
    }
}
