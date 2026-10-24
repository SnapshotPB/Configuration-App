package com.snapshotpb.configuration.bluetooth.smp

import com.snapshotpb.configuration.bluetooth.FakeBluetoothPeripheral
import com.snapshotpb.configuration.bluetooth.FakeBluetoothService
import com.snapshotpb.configuration.bluetooth.FakeOtsCharacteristic
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmpDiscoveryTest {

    @Test
    fun aBoardOnAnOlderFirmwareHasNoSmpService() {
        val oldBoard = FakeBluetoothPeripheral(uuid = "old", services = listOf(FakeBluetoothService(OtsUuids.Service)))

        assertFalse(hasSmpService(oldBoard))
        assertNull(findSmpService(oldBoard))
        assertNull(findSmpCharacteristic(oldBoard))
    }

    @Test
    fun aServiceWithNoCharacteristicGivesNoCharacteristic() {
        // As MacosJvmEngine before discoverCharacteristics.
        val board = FakeBluetoothPeripheral(uuid = "board", services = listOf(FakeBluetoothService(SmpUuids.Service)))

        assertTrue(hasSmpService(board))
        assertNull(findSmpCharacteristic(board))
    }

    @Test
    fun findsTheCharacteristicInTheSmpService() {
        val characteristic = FakeOtsCharacteristic(SmpUuids.Characteristic)
        val board = FakeBluetoothPeripheral(
            uuid = "board",
            services = listOf(
                FakeBluetoothService(OtsUuids.Service),
                FakeBluetoothService(SmpUuids.Service, characteristics = listOf(characteristic)),
            ),
        )

        assertSame(characteristic, findSmpCharacteristic(board))
    }
}
