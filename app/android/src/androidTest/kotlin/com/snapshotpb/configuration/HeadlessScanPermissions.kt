package com.snapshotpb.configuration

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

/**
 * Grants the runtime permissions that a hardware test with no screen needs for a BLE scan.
 *
 * The app asks for BLUETOOTH_SCAN and BLUETOOTH_CONNECT from API 31, and for location before that.
 * A permission that the running OS does not know throws, so each grant has its own `runCatching`.
 *
 * Android 10 and 11 give scan results to a background app only when it has
 * ACCESS_BACKGROUND_LOCATION. A test with no screen is a background app. The debug manifest
 * declares the permission for these two versions. Without it, the scan finds nothing and the test
 * only stops at its time limit, so this function fails at once when the app does not have it.
 *
 * `ConnectivityE2ETest` does not use this function. Its activity is in the foreground, and a
 * release build cannot have the permission.
 */
internal fun grantHeadlessScanPermissions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    listOf(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        BACKGROUND_LOCATION,
    ).forEach { permission ->
        runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission) }
    }
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
        assertEquals(
            "The app does not have ACCESS_BACKGROUND_LOCATION, so Android gives this test no scan " +
                "results. Install the debug APK of this build with `adb install`, then run the test again.",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(BACKGROUND_LOCATION),
        )
    }
}
