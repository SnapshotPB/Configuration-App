package com.snapshotpb.configuration.media

import android.Manifest.permission.CAMERA
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import com.toxicbakery.logging.Arbor

class MediaManager(
    private val mediaStateManager: MediaStateManager,
) {
    fun permissionGranted() = mediaStateManager.update(MediaState.Ready)

    fun needsPermission() = mediaStateManager.update(MediaState.NeedsPermission)

    companion object {
        private val log = Arbor.tag("MediaManager")
        private val permissions = arrayOf(CAMERA)

        fun ComponentActivity.hasMediaPermission(): Boolean =
            permissions.fold(true) { acc, permission ->
                val checkResult =
                    checkSelfPermission(this@hasMediaPermission, permission) == PERMISSION_GRANTED
                log.d { "$permission granted: $checkResult" }
                acc && checkResult
            }

        fun ComponentActivity.requestMediaPermissions(requestCode: Int) {
            requestPermissions(permissions, requestCode)
        }
    }
}
