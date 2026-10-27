package com.snapshotpb.configuration.store

import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.FIRE_RATE_CAP_DEFAULT
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.matchingGripFrame
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AppJsonTest {

    private val deviceListSerializer = ListSerializer(Device.serializer())

    private fun storedDeviceJsonWithRemovedFireMode(): String {
        // A profile with a non-default fire mode so it is actually written (encodeDefaults=false),
        // then rewritten to a constant that no longer exists in MarkerFireMode — exactly what a
        // devices.json persisted by an older build looks like after the enum shrank.
        val device = Device(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Old Device",
            lastSeen = LocalDate(2026, 1, 1),
            knownProfiles = listOf(
                MarkerProfile(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                    name = "P0",
                    fireMode = MarkerFireMode.FULL_AUTO,
                ),
            ),
        )
        val encoded = appJson.encodeToString(deviceListSerializer, listOf(device))
        val stale = encoded.replace("\"FULL_AUTO\"", "\"RAMPING_PSP\"")
        // Guard the test itself: the removed constant must really be in the payload we decode.
        assertTrue(stale.contains("RAMPING_PSP"), "test setup failed to inject the removed enum value")
        return stale
    }

    @Test
    fun keepsTheSourceSlotOfAProfileThroughARoundTrip() {
        val device = Device(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Device",
            lastSeen = LocalDate(2026, 1, 1),
            knownProfiles = listOf(
                MarkerProfile(id = Uuid.parse("00000000-0000-0000-0000-000000000002"), name = "P0", sourceSlot = 2),
                MarkerProfile(id = Uuid.parse("00000000-0000-0000-0000-000000000003"), name = "P1"),
            ),
        )

        val decoded = appJson.decodeFromString(
            deviceListSerializer,
            appJson.encodeToString(deviceListSerializer, listOf(device)),
        ).single()

        assertEquals(listOf(2, null), decoded.knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun decodesAProfileStoredBeforeTheSourceSlotExisted() {
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","bootText":"Hello",
              "knownProfiles":[{"id":"00000000-0000-0000-0000-000000000002","name":"P0"}]}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertNull(device.knownProfiles.single().sourceSlot)
    }

    @Test
    fun decodesADeviceStoredBeforeTheBoardLanguageExisted() {
        // devices.json from an older build: a baseline, and no "language" key at either level.
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","knownProfiles":[],"bootText":"Hello",
              "boardState":{"bootText":"Hello","activeProfileId":null,
                            "lockoutEnabled":false,"knownProfiles":[]}}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertNull(device.language)
        assertNull(device.boardState?.language)
        assertFalse(device.hasDiverged(), "an app update must not show an unsaved change")
    }

    @Test
    fun decodesADeviceThatStoredTheE1Rectangle() {
        // Only the rectangle is stored, so a device with the E1 rectangle loads and matches the E1
        // grip frame preset.
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","knownProfiles":[],"bootText":"Hello",
              "displayWindow":{"x":0,"y":3,"w":128,"h":26},
              "boardState":{"bootText":"Hello","activeProfileId":null,
                            "lockoutEnabled":false,"knownProfiles":[],
                            "displayWindow":{"x":0,"y":3,"w":128,"h":26}}}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertEquals(GripFrame.E1.window, device.displayWindow)
        assertEquals(GripFrame.E1.window, device.boardState?.displayWindow)
        assertEquals(GripFrame.E1, device.displayWindow?.matchingGripFrame())
        assertFalse(device.hasDiverged())
    }

    @Test
    fun decodesADeviceStoredBeforeTheScreenSettingsExisted() {
        // The screen brightness moved off Profile and onto DeviceConfig, and the screen-off delay
        // is new. Neither key is in a record written before the move.
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","knownProfiles":[],"bootText":"Hello",
              "boardState":{"bootText":"Hello","activeProfileId":null,
                            "lockoutEnabled":false,"knownProfiles":[]}}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertNull(device.screenBrightness)
        assertNull(device.screenOffDelay)
        assertNull(device.boardState?.screenBrightness)
        assertNull(device.boardState?.screenOffDelay)
        assertFalse(device.hasDiverged(), "an app update must not show an unsaved change")
    }

    @Test
    fun decodesADeviceStoredWhileProfilesStillCarriedAType() {
        // MarkerProfile.type (the retired ProfileType) told the app how many fields to show; it
        // never reached the board's behaviour, so it was deleted. Records written before that
        // still carry the key at both the profile and the board-state level.
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","bootText":"Hello",
              "knownProfiles":[{"id":"00000000-0000-0000-0000-000000000002",
                                "name":"P0","type":"CUSTOM"}],
              "boardState":{"bootText":"Hello","activeProfileId":null,"lockoutEnabled":false,
                            "knownProfiles":[{"id":"00000000-0000-0000-0000-000000000002",
                                              "name":"P0","type":"CUSTOM"}]}}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertEquals("P0", device.knownProfiles.single().name)
        assertFalse(device.hasDiverged(), "an app update must not show an unsaved change")
    }

    @Test
    fun decodesADeviceStoredWhileTheFireRateCapCountedWholeBallsPerSecond() {
        // The cap now counts TENTHS of a ball per second, so the stored key moved from
        // "fireRateCap" to "fireRateCapTenths". A record written by an older build carries the old
        // key, which decodes as an unknown key: the profile falls back to the app default rather
        // than reading 13 as 1.3 BPS. Both levels lose the key together, so no unsaved badge.
        val stored = """
            [{"id":"00000000-0000-0000-0000-000000000001","name":"Old Device",
              "lastSeen":"2026-01-01","bootText":"Hello",
              "knownProfiles":[{"id":"00000000-0000-0000-0000-000000000002",
                                "name":"P0","fireRateCap":13}],
              "boardState":{"bootText":"Hello","activeProfileId":null,"lockoutEnabled":false,
                            "knownProfiles":[{"id":"00000000-0000-0000-0000-000000000002",
                                              "name":"P0","fireRateCap":13}]}}]
        """.trimIndent()

        val device = appJson.decodeFromString(deviceListSerializer, stored).single()

        assertEquals(FIRE_RATE_CAP_DEFAULT, device.knownProfiles.single().fireRateCap)
        assertEquals(FIRE_RATE_CAP_DEFAULT, device.boardState?.knownProfiles?.single()?.fireRateCap)
        assertFalse(device.hasDiverged(), "an app update must not show an unsaved change")
    }

    @Test
    fun writesTheFireRateCapUnderTheTenthsKey() {
        // Guards the rename itself: a reader of devices.json must never meet the old key again.
        val device = Device(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Device",
            lastSeen = LocalDate(2026, 1, 1),
            knownProfiles = listOf(
                MarkerProfile(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                    name = "P0",
                    fireRateCap = 120,
                ),
            ),
        )

        val encoded = appJson.encodeToString(deviceListSerializer, listOf(device))

        assertTrue(encoded.contains("\"fireRateCapTenths\":120"), encoded)
        assertFalse(encoded.contains("\"fireRateCap\":"), encoded)
    }

    @Test
    fun coercesRemovedEnumConstantToDefaultInsteadOfFailingTheLoad() {
        val decoded = appJson.decodeFromString(deviceListSerializer, storedDeviceJsonWithRemovedFireMode())

        assertEquals(1, decoded.size)
        // The retired mode degrades to the MarkerProfile.fireMode default rather than throwing.
        assertEquals(MarkerFireMode.SEMI, decoded[0].knownProfiles[0].fireMode)
    }

    @Test
    fun withoutCoercionTheSameStoredJsonFailsToDecode() {
        // Documents why appJson keeps coerceInputValues: the same data blows up the whole
        // List<Device> decode without it, which is how the device store silently emptied.
        val strict = Json { ignoreUnknownKeys = true }
        assertFailsWith<SerializationException> {
            strict.decodeFromString(deviceListSerializer, storedDeviceJsonWithRemovedFireMode())
        }
    }

    private fun deviceWithFireMode(fireMode: MarkerFireMode) = Device(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        name = "Ramp Board",
        lastSeen = LocalDate(2026, 1, 1),
        knownProfiles = listOf(
            MarkerProfile(id = Uuid.parse("00000000-0000-0000-0000-000000000002"), name = "P0", fireMode = fireMode),
        ),
    )

    @Test
    fun theNxlModeKeepsTheNameThatOlderBuildsStored() {
        // Before the protocol had a custom ramping mode, the one ramping mode of the app was the
        // NXL 2026 mode under the name RAMPING_CUSTOM. A stored profile and a Wear peer on an
        // older build still use that name, so it must keep that meaning in both directions.
        val encoded = appJson.encodeToString(
            deviceListSerializer,
            listOf(deviceWithFireMode(MarkerFireMode.RAMPING_NXL_2026)),
        )
        assertTrue(encoded.contains("\"fireMode\":\"RAMPING_CUSTOM\""), encoded)

        val decoded = appJson.decodeFromString(deviceListSerializer, encoded)
        assertEquals(MarkerFireMode.RAMPING_NXL_2026, decoded.single().knownProfiles.single().fireMode)
    }

    @Test
    fun theCustomRampingModeHasItsOwnStoredName() {
        val encoded = appJson.encodeToString(
            deviceListSerializer,
            listOf(deviceWithFireMode(MarkerFireMode.CUSTOM_RAMPING)),
        )
        assertTrue(encoded.contains("\"fireMode\":\"CUSTOM_RAMPING\""), encoded)

        val decoded = appJson.decodeFromString(deviceListSerializer, encoded)
        assertEquals(MarkerFireMode.CUSTOM_RAMPING, decoded.single().knownProfiles.single().fireMode)
    }

    @Test
    fun anUnknownModeSurvivesAStoreRoundTrip() {
        val device = Device(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "Newer Board",
            lastSeen = LocalDate(2026, 1, 1),
            knownProfiles = listOf(
                MarkerProfile(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                    name = "P0",
                    fireMode = MarkerFireMode.UNKNOWN,
                    eyeMode = MarkerEyeMode.UNKNOWN,
                ),
            ),
        )

        val encoded = appJson.encodeToString(deviceListSerializer, listOf(device))
        val profile = appJson.decodeFromString(deviceListSerializer, encoded).single().knownProfiles.single()

        assertEquals(MarkerFireMode.UNKNOWN, profile.fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, profile.eyeMode)
    }
}
