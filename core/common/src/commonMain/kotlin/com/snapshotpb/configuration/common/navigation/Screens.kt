package com.snapshotpb.configuration.common.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed class ScreenKey : NavKey

@Serializable
object ScreenKeyDeviceBrowser : ScreenKey()

@Serializable
data class ScreenKeyProfiles(
    val deviceId: String,
) : ScreenKey()

@Serializable
data class ScreenKeyProfile(
    val deviceId: String,
    val profileId: String,
) : ScreenKey()

@Serializable
data class ScreenKeyProfileAdd(
    val deviceId: String,
) : ScreenKey()

@Serializable
data class ScreenKeyDeviceConfigure(
    val deviceId: String,
) : ScreenKey()

@Serializable
data class ScreenKeyConnectingDevice(
    val deviceId: String,
) : ScreenKey()

/** The firmware update of the board [deviceId]: the warnings, the steps and the result. */
@Serializable
data class ScreenKeyFirmwareUpdate(
    val deviceId: String,
) : ScreenKey()
