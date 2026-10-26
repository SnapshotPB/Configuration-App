package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.firmware.FirmwareFeedException
import com.snapshotpb.configuration.firmware.FirmwareHttp
import kotlinx.coroutines.delay

/**
 * The root URL of the simulated firmware site. The `.invalid` domain cannot resolve, so no request
 * to it can reach a network.
 */
internal const val SIMULATED_FIRMWARE_SITE_URL = "https://simulated.invalid/firmware"

/** The board name of the simulated board, as `os info -f i` of a `snapshot_v2` board gives it. */
internal const val SIMULATED_BOARD = "snapshot_v2"

/** The version of the one release on the simulated site. It is newer than the simulated board. */
internal const val SIMULATED_RELEASE_VERSION = "2026.43.1"

/** The length of the simulated image. */
private const val SIMULATED_IMAGE_SIZE = 64 * 1024

/**
 * The SHA-256 of [simulatedImage]. `SimulatedFirmwareSiteTest` checks it, so a change to the image
 * fails the test and not the Demo.
 */
internal const val SIMULATED_IMAGE_SHA256 = "7bc6ec89e34b3b9b3b0fd92b5568095fc59c6b44bd48f926262c7a6599e4cef2"

private const val HTTP_NOT_FOUND = 404
private const val DOWNLOAD_STEPS = 8
private const val DOWNLOAD_STEP_DELAY_MILLIS = 150L

private const val IMAGE_PATH =
    "$SIMULATED_BOARD/$SIMULATED_RELEASE_VERSION/$SIMULATED_BOARD-$SIMULATED_RELEASE_VERSION-app.signed.bin"
private const val IMAGE_URL = "$SIMULATED_FIRMWARE_SITE_URL/$IMAGE_PATH"

/** The release entry, in the format of the site generator (`scripts/pages_index.py`). */
private const val RELEASE_JSON = """
{
  "version": "$SIMULATED_RELEASE_VERSION",
  "tag": "v$SIMULATED_RELEASE_VERSION",
  "channel": "stable",
  "compatible_hardware": ["$SIMULATED_BOARD"],
  "warning": "Simulated release of the Demo board. It is not firmware.",
  "assets": {
    "image": {
      "name": "$SIMULATED_BOARD-$SIMULATED_RELEASE_VERSION-app.signed.bin",
      "path": "$IMAGE_PATH",
      "url": "$IMAGE_URL",
      "size": $SIMULATED_IMAGE_SIZE,
      "sha256": "$SIMULATED_IMAGE_SHA256"
    }
  }
}
"""

private const val LATEST_JSON = RELEASE_JSON

private const val INDEX_JSON = """
{
  "schema": 1,
  "board": "$SIMULATED_BOARD",
  "releases": [$RELEASE_JSON]
}
"""

/**
 * A firmware site with one release for the simulated board, held in memory. With it, the Demo
 * entry shows the check and the download of an update with no network. The image is a byte
 * pattern, not firmware: the simulated board takes it and does nothing with it.
 *
 * Only `core:fake-board` holds this site, so it never reaches a release binary.
 */
internal class SimulatedFirmwareSite : FirmwareHttp {

    override suspend fun getText(url: String): String = when (url) {
        "$SIMULATED_FIRMWARE_SITE_URL/$SIMULATED_BOARD/latest.json" -> LATEST_JSON
        "$SIMULATED_FIRMWARE_SITE_URL/$SIMULATED_BOARD/index.json" -> INDEX_JSON
        else -> throw FirmwareFeedException.Http(url, HTTP_NOT_FOUND)
    }

    /** Gives the image in steps, so the Demo shows the progress of a download. */
    override suspend fun getBytes(
        url: String,
        maxBytes: Long,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): ByteArray {
        if (url != IMAGE_URL) throw FirmwareFeedException.Http(url, HTTP_NOT_FOUND)
        val image = simulatedImage()
        val total = image.size.toLong()
        for (step in 1..DOWNLOAD_STEPS) {
            delay(DOWNLOAD_STEP_DELAY_MILLIS)
            onProgress(total * step / DOWNLOAD_STEPS, total)
        }
        return image
    }
}

/** The bytes of the simulated image: a fixed pattern with the hash [SIMULATED_IMAGE_SHA256]. */
internal fun simulatedImage(): ByteArray =
    ByteArray(SIMULATED_IMAGE_SIZE) { index -> ((index * 31 + 7) xor (index ushr 8)).toByte() }
