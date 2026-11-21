package com.snapshotpb.configuration.share

import androidx.core.content.FileProvider

/**
 * Gives shared Bluetooth log files from the app cache to other apps.
 *
 * This subclass prevents a manifest merge conflict with a FileProvider from a library.
 */
class BluetoothLogFileProvider : FileProvider()
