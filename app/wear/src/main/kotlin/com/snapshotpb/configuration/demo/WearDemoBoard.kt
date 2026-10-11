package com.snapshotpb.configuration.demo

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.model.DiscoveredDevice

/**
 * Seam that lets a debug build of the watch answer Bluetooth requests with a demo board, so the
 * E2E tests and a preview of the watch UI need no real marker.
 *
 * The demo board lives in the `debug` source set. A release build compiles the `release` copy of
 * `WearDemoModule.kt`, which binds [NoWearDemoBoard], so no part of the demo board reaches a
 * release APK. The phone app uses the same arrangement for its `DebugBoard`.
 */
interface WearDemoBoard {

    /** True while the demo board answers requests. Always false in a release build. */
    val active: Boolean

    /**
     * Turns the demo board on or off. The activity reads the switch from its launch intent, so
     * this seam stays free of Android types.
     */
    fun applySwitch(on: Boolean)

    /**
     * Services [request] and returns true when the demo board answered it. A false return leaves
     * the request to the real radio.
     */
    suspend fun handle(request: BluetoothRequest): Boolean

    /**
     * Adds the demo board to the boards a scan found. The demo board shows only while the demo
     * board is [active] and a scan runs, which is when a real board shows.
     */
    fun withDemoDevice(real: List<DiscoveredDevice>, scanning: Boolean): List<DiscoveredDevice>
}

/** The release binding. No demo board exists, so every member is inert. */
object NoWearDemoBoard : WearDemoBoard {

    override val active: Boolean = false

    override fun applySwitch(on: Boolean) = Unit

    override suspend fun handle(request: BluetoothRequest): Boolean = false

    override fun withDemoDevice(real: List<DiscoveredDevice>, scanning: Boolean): List<DiscoveredDevice> =
        real
}
