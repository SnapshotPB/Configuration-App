package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.emulator.EmulatorEngine
import com.snapshotpb.configuration.bluetooth.emulator.FakeOtsBridgeBoard
import com.snapshotpb.configuration.bluetooth.log.BoardLogUuids
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.bluetooth.smp.SmpUuids
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.ServiceFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserBluetoothSeamsTest {

    @Test
    fun scanFilters_includesBoardLogServiceAfterPreviousFilters() {
        val expected = SnapshotAdvertisement.scanFilters + listOf(
            ServiceFilter(OtsUuids.Service),
            ServiceFilter(OtsUuids.DeviceObjectType),
            ServiceFilter(OtsUuids.ObjectData),
            ServiceFilter(SmpUuids.Service),
            ServiceFilter(BoardLogUuids.Service),
        )

        assertEquals(expected, scanFilters)
    }

    @Test
    fun servicesFollowPermission_reflectsConstructorParameter() = runTest {
        val blueFalcon = BlueFalcon(EmulatorEngine(FakeOtsBridgeBoard().bridge, backgroundScope))
        val stateManager = BluetoothStateManager()

        assertTrue(
            BrowserBluetoothSeams(
                blueFalcon = blueFalcon,
                bluetoothStateManager = stateManager,
                servicesFollowPermission = true,
            ).servicesFollowPermission,
        )
        assertFalse(
            BrowserBluetoothSeams(
                blueFalcon = blueFalcon,
                bluetoothStateManager = stateManager,
                servicesFollowPermission = false,
            ).servicesFollowPermission,
        )
    }
}
