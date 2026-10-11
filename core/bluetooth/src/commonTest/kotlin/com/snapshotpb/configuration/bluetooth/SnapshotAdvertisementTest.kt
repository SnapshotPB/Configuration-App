package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.ServiceFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** The advertisement rules of #128, with the values of the firmware `app/Kconfig`. */
class SnapshotAdvertisementTest {

    private class AdvertisingPeripheral(
        override val uuid: String,
        override val advertisedServiceUUIDs: List<Uuid>,
        override val name: String? = null,
    ) : BluetoothPeripheral {
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override val services: List<BluetoothService> = emptyList()
        override val characteristics: List<BluetoothCharacteristic> = emptyList()
    }

    @Test
    fun serviceUuids_areTheFirmwareValuesOnTheBluetoothBase() {
        assertEquals("0000ffe2-0000-1000-8000-00805f9b34fb", SnapshotAdvertisement.FilterService.toString())
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", SnapshotAdvertisement.BoardInfoService.toString())
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", SnapshotAdvertisement.IdentityService.toString())
    }

    @Test
    fun boardScanKeys_useTheServiceUuidAndNotTheName() {
        val state = BluetoothDeviceState(
            devices = mapOf(
                // A board with no name in its advertisement is still a board.
                "board" to AdvertisingPeripheral("board", listOf(SnapshotAdvertisement.FilterService)),
                // The name alone does not make a board.
                "impostor" to AdvertisingPeripheral(
                    "impostor",
                    listOf(SnapshotAdvertisement.BoardInfoService),
                    name = "Snapshot PB",
                ),
                // An engine that reports no service UUIDs applied the scan filter itself.
                "web" to AdvertisingPeripheral("web", emptyList()),
            )
        )

        assertEquals(listOf("board", "web"), state.boardScanKeys())
        assertFalse(SnapshotAdvertisement.isSnapshotBoard(state.devices.getValue("impostor")))
    }

    @Test
    fun boardIdOf_readsTheIdentityServiceData() {
        // bluetooth.c copies the 16 bytes of device_id after the 0xFFE1 UUID. Android gives the
        // service data without the UUID.
        val deviceId = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")

        assertEquals(deviceId.toString(), SnapshotAdvertisement.boardIdOf(deviceId.toByteArray()))
        // The firmware sends zeros when it cannot read its device state.
        assertNull(SnapshotAdvertisement.boardIdOf(ByteArray(16)))
        assertNull(SnapshotAdvertisement.boardIdOf(ByteArray(15) { 1 }))
    }

    @Test
    fun defaultSeams_scanWithTheBoardServiceFilter() = runTest {
        val engine = FakeBlueFalconEngine(backgroundScope)
        val seams = DefaultBluetoothSeams(BlueFalcon(engine), BluetoothStateManager())

        seams.scanForDevices()

        assertEquals(listOf(listOf(ServiceFilter(SnapshotAdvertisement.FilterService))), engine.scanFilters)
    }
}
