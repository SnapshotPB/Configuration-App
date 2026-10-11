package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cover for how the configuration draft takes its values from the stored device. */
class ConfigureDeviceDraftTest {

    @Test
    fun hydratedFrom_nullKeepsTheDraftEmpty() {
        val draft = ConfigureDeviceDraft().hydratedFrom(null)

        assertFalse(draft.hydrated, "Save stays disabled until the store has the device")
    }

    @Test
    fun hydratedFrom_theFirstDeviceFillsTheWholeDraft() {
        val device = testDevice(
            name = "Marker",
            bootText = "Hello",
            language = BoardLanguage.DE,
            screenOffDelay = 30,
        )

        val draft = ConfigureDeviceDraft().hydratedFrom(device)

        assertTrue(draft.hydrated)
        assertEquals("Marker", draft.deviceName)
        assertEquals("Hello", draft.bootText)
        assertEquals(BoardLanguage.DE, draft.language)
        assertEquals(30, draft.screenOffDelay)
    }

    @Test
    fun hydratedFrom_aLaterDeviceFillsOnlyEmptyBoardSettings() {
        // The user renamed the device and chose a language. Then the board read finished and
        // reported its own language and a brightness.
        val edited = ConfigureDeviceDraft()
            .hydratedFrom(testDevice(name = "Marker"))
            .copy(deviceName = "Renamed", language = BoardLanguage.FR)

        val draft = edited.hydratedFrom(
            testDevice(
                name = "Marker",
                language = BoardLanguage.EN,
                screenBrightness = BoardScreenBrightness.HIGH,
            )
        )

        assertEquals("Renamed", draft.deviceName, "an edit is never replaced")
        assertEquals(BoardLanguage.FR, draft.language, "a choice is never replaced")
        assertEquals(BoardScreenBrightness.HIGH, draft.screenBrightness, "an empty setting fills")
    }
}
