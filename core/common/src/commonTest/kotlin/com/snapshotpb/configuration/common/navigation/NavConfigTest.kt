package com.snapshotpb.configuration.common.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.PolymorphicSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class NavConfigTest {

    @Test
    fun bluetoothLogKeyRoundTripsThroughNavConfig() {
        val serializer = PolymorphicSerializer(NavKey::class)
        val savedState = encodeToSavedState(serializer, ScreenKeyBluetoothLog, navConfig)
        val decoded = decodeFromSavedState(serializer, savedState, navConfig)

        assertEquals(ScreenKeyBluetoothLog, decoded)
    }

    @Test
    fun deviceBrowserKeyRoundTripsThroughNavConfig() {
        val serializer = PolymorphicSerializer(NavKey::class)
        val savedState = encodeToSavedState(serializer, ScreenKeyDeviceBrowser, navConfig)
        val decoded = decodeFromSavedState(serializer, savedState, navConfig)

        assertEquals(ScreenKeyDeviceBrowser, decoded)
    }
}
