package com.snapshotpb.configuration.share

import java.io.File

internal object SharedLogFiles {
    /** The folder in the cache folder. It agrees with `res/xml/bluetooth_log_paths.xml`. */
    const val DIRECTORY = "shared-logs"
    private const val AUTHORITY_SUFFIX = ".bluetoothlog"

    /** The authority of [BluetoothLogFileProvider]. It agrees with the manifest. */
    fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    /**
     * Deletes the old files in `<cacheDir>/shared-logs`, then writes [text] as UTF-8 to a new file.
     * The file name is the last path segment of [fileName], so the file stays in the folder.
     * A blank name throws IllegalArgumentException.
     */
    fun write(cacheDir: File, fileName: String, text: String): File {
        val safeName = File(fileName).name
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") {
            "The file name must not be blank."
        }
        val directory = File(cacheDir, DIRECTORY)
        directory.mkdirs()
        directory.listFiles()?.forEach { entry ->
            if (entry.isFile) {
                entry.delete()
            }
        }
        val target = File(directory, safeName)
        target.writeText(text, Charsets.UTF_8)
        return target
    }
}
