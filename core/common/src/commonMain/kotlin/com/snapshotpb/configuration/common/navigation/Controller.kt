package com.snapshotpb.configuration.common.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

internal val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        // Register every ScreenKey subclass declared in Screens.kt. Keep this list in
        // sync with the sealed hierarchy — an unregistered key crashes saved-state
        // (de)serialization on process death / config change.
        polymorphic(NavKey::class) {
            subclass(ScreenKeyConnectingDevice::class, ScreenKeyConnectingDevice.serializer())
            subclass(ScreenKeyDeviceBrowser::class, ScreenKeyDeviceBrowser.serializer())
            subclass(ScreenKeyDeviceConfigure::class, ScreenKeyDeviceConfigure.serializer())
            subclass(ScreenKeyProfile::class, ScreenKeyProfile.serializer())
            subclass(ScreenKeyProfileAdd::class, ScreenKeyProfileAdd.serializer())
            subclass(ScreenKeyProfiles::class, ScreenKeyProfiles.serializer())
        }
    }
}

class NavigationController(
    private val backStack: NavBackStack<NavKey>
) {
    fun goTo(screenKey: ScreenKey) {
        backStack.add(screenKey)
    }

    fun replaceWith(screenKey: ScreenKey) {
        backStack.removeLastOrNull()
        backStack.add(screenKey)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun navigateBackToProfiles(deviceId: String) {
        backStack.removeLastOrNull()
        val last = backStack.lastOrNull()
        if (last !is ScreenKeyProfiles || last.deviceId != deviceId) {
            backStack.add(ScreenKeyProfiles(deviceId))
        }
    }
}
