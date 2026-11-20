package com.snapshotpb.configuration.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cover for JSON backward compatibility of [Device] and [BoardState]. */
class DeviceJsonTest {

    private val storeJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    @Test
    fun storedDeviceWithoutBluetoothAtBootDecodesAsNull() {
        val raw = """
            {
                "id": "11111111-2222-3333-4444-555555555555",
                "name": "Marker",
                "lastSeen": "2026-09-01",
                "knownProfiles": [],
                "boltMode": "CLOSED",
                "boardState": {
                    "bootText": "SnapshotPB",
                    "activeProfileId": null,
                    "lockoutEnabled": false,
                    "knownProfiles": [],
                    "boltMode": "CLOSED"
                }
            }
        """.trimIndent()

        val decoded = storeJson.decodeFromString(Device.serializer(), raw)

        assertNull(decoded.bluetoothAtBoot)
        assertNull(decoded.boardState?.bluetoothAtBoot)
        assertEquals(MarkerBoltMode.CLOSED, decoded.boltMode)
    }

    @Test
    fun bluetoothAtBootRoundTripsInJson() {
        val raw = """
            {
                "id": "11111111-2222-3333-4444-555555555555",
                "name": "Marker",
                "lastSeen": "2026-09-01",
                "knownProfiles": [],
                "bluetoothAtBoot": "RESTORE",
                "boardState": {
                    "bootText": "SnapshotPB",
                    "activeProfileId": null,
                    "lockoutEnabled": false,
                    "knownProfiles": [],
                    "bluetoothAtBoot": "OFF"
                }
            }
        """.trimIndent()

        val decoded = storeJson.decodeFromString(Device.serializer(), raw)
        val encoded = storeJson.encodeToString(Device.serializer(), decoded)
        val reparsed = storeJson.decodeFromString(Device.serializer(), encoded)

        assertEquals(BoardBluetoothAtBoot.RESTORE, reparsed.bluetoothAtBoot)
        assertEquals(BoardBluetoothAtBoot.OFF, reparsed.boardState?.bluetoothAtBoot)
    }
}
