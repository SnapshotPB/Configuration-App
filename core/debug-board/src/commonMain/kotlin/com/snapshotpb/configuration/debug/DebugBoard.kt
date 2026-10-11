package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import kotlinx.coroutines.flow.Flow

/**
 * Seam that lets a debug build answer Bluetooth requests with a simulated board, so the app runs on
 * an emulator that has no radio and no real marker near it.
 *
 * The simulated board itself lives in `core:fake-board`, which a release configuration never puts on
 * the compile classpath: Android depends on it through `debugImplementation`, and iOS through the
 * `iosDebug` source set that the framework's configuration selects. A release build binds
 * [NoDebugBoard] instead, so no part of the simulated board reaches a release binary on either
 * platform. Every hook here is inert in that build: [active] stays false, [handle] claims no
 * request, and [deviceState] hands the real scan flow straight back.
 *
 * The seam holds no platform types, so both app entry points drive it the same way — the Android
 * activity reads the switch off its launch intent, the iOS entry point off its launch arguments.
 */
interface DebugBoard {

    /** True while the simulated board answers Bluetooth requests. Always false in a release build. */
    val active: Boolean

    /**
     * Applies the on/off switch, where `null` means "leave it as it was". The activity calls this
     * before it reports the real radio state, because an active simulated board owns that state.
     * The activity parses the launch intent itself, so this seam stays free of Android types.
     */
    fun applySwitch(on: Boolean?)

    /**
     * Services [request] and returns true when the simulated board answered it. A false return
     * leaves the request to the real radio.
     */
    suspend fun handle(request: BluetoothRequest): Boolean

    /**
     * Wraps the real discovered-device flow. While the simulated board is [active], the flow that
     * comes back also carries it, so it appears in the device browser.
     */
    fun deviceState(real: Flow<BluetoothDeviceState>): Flow<BluetoothDeviceState>
}

/** The release binding. No simulated board exists, so every member is inert. */
object NoDebugBoard : DebugBoard {

    override val active: Boolean = false

    override fun applySwitch(on: Boolean?) = Unit

    override suspend fun handle(request: BluetoothRequest): Boolean = false

    override fun deviceState(real: Flow<BluetoothDeviceState>): Flow<BluetoothDeviceState> = real
}
