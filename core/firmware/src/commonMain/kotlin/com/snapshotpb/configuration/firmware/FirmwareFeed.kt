package com.snapshotpb.configuration.firmware

import com.snapshotpb.configuration.model.FIRMWARE_SITE_SCHEMA
import com.snapshotpb.configuration.model.FirmwareBoardIndex
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareImage
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareSiteIndex
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * The root URL of the firmware site, with no `/` at the end. The `Firmware` repository publishes
 * the site on GitHub Pages (`docs/firmware-distribution-pages-plan.md` in that repository).
 */
const val FIRMWARE_SITE_URL = "https://snapshotpb.github.io/Firmware"

/**
 * The largest image that the feed downloads. The update image of a board is about 900 KB, and the
 * image slot of the ESP32-C3 holds less than this.
 */
const val MAX_FIRMWARE_IMAGE_SIZE = 4L * 1024 * 1024

/**
 * Reads the firmware releases of a board from the firmware site, and downloads and checks an
 * update image.
 *
 * The site (`scripts/pages_index.py` in the `Firmware` repository) has these files:
 *
 * ```
 * <site>/index.json                  the boards and the newest version of each channel
 * <site>/<board>/index.json          every release of the board, newest first
 * <site>/<board>/latest.json         the newest stable release of the board
 * <site>/<board>/<version>/<asset>   the image, the manifest and the SHA256SUMS
 * ```
 *
 * The feed ignores keys that it does not know, so a new key on the site does not stop an older
 * app. It refuses a file with a `schema` greater than [FIRMWARE_SITE_SCHEMA].
 *
 * [download] gives an image only when its size and its SHA-256 are the values of the site. The
 * feed keeps the last good image in memory, so a second attempt of an update with the same
 * release does not download the image again.
 *
 * @param http The HTTP access.
 * @param siteUrl The root URL of the site. A bench test gives the URL of a local site.
 * @param hashDispatcher The dispatcher of the SHA-256 calculation.
 */
class FirmwareFeed(
    private val http: FirmwareHttp,
    siteUrl: String = FIRMWARE_SITE_URL,
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The root URL of the site, with no `/` at the end. */
    val siteUrl: String = siteUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        // An unknown channel name becomes a null channel, which the app offers on no channel.
        coerceInputValues = true
    }

    private val downloadMutex = Mutex()

    /** The last image whose size and hash agreed with the site. [downloadMutex] guards it. */
    private var lastImage: FirmwareImage? = null

    /** Reads the root `index.json`: the boards of the site. */
    suspend fun boards(): FirmwareSiteIndex = read("$siteUrl/index.json")

    /**
     * Reads `<board>/index.json`: every release of [board], newest first. Returns `null` when the
     * site has no file for the board.
     */
    suspend fun releases(board: String): FirmwareBoardIndex? {
        if (!isSiteBoardName(board)) return null
        return readOrNull("$siteUrl/$board/index.json")
    }

    /**
     * The newest release of [board] in [channel], or `null` when the site has none.
     *
     * For [FirmwareChannel.STABLE] the feed reads `<board>/latest.json` only, which is about 2 KB.
     * For [FirmwareChannel.BETA] it reads `<board>/index.json` and takes the newest release of the
     * beta or the stable channel: a stable release that is newer than every beta release is also
     * the newest release for a beta user.
     */
    suspend fun latest(board: String, channel: FirmwareChannel = FirmwareChannel.STABLE): FirmwareManifest? {
        if (!isSiteBoardName(board)) return null
        return when (channel) {
            FirmwareChannel.STABLE -> readOrNull("$siteUrl/$board/latest.json")
            FirmwareChannel.BETA -> {
                val index = readOrNull<FirmwareBoardIndex>("$siteUrl/$board/index.json")
                // A release whose version does not parse can get no offer, so the feed drops it.
                index?.releases
                    ?.filter { it.channel in BETA_CHANNELS }
                    ?.mapNotNull { release -> release.firmwareVersion?.let { release to it } }
                    ?.maxByOrNull { (_, version) -> version }
                    ?.first
            }
        }
    }

    /**
     * Downloads the image of [release] from `assets.image.url`, and checks its size and its
     * SHA-256 against the values of the site.
     *
     * When the last good image has the same hash, the feed gives a copy of it and does not
     * download again.
     *
     * @param onProgress Receives the bytes that arrived and the length of the image.
     * @throws FirmwareFeedException.BadSize for an image of a different length.
     * @throws FirmwareFeedException.BadHash for an image of a different SHA-256.
     * @throws FirmwareFeedException.BadJson when the site gives no valid size or hash.
     * @throws FirmwareFeedException.Http when the site does not give the image.
     */
    suspend fun download(
        release: FirmwareManifest,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): FirmwareImage = downloadMutex.withLock { downloadLocked(release, onProgress) }

    private suspend fun downloadLocked(
        release: FirmwareManifest,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): FirmwareImage {
        val asset = release.assets.image
        val expectedHash = asset.sha256.lowercase()
        if (!SHA256_TEXT.matches(expectedHash)) {
            throw FirmwareFeedException.BadJson(asset.url, "the image has no valid sha256")
        }
        if (asset.size !in 1..MAX_FIRMWARE_IMAGE_SIZE) {
            throw FirmwareFeedException.BadJson(asset.url, "the image size ${asset.size} is not valid")
        }

        lastImage?.takeIf { image ->
            image.manifest.assets.image.sha256.lowercase() == expectedHash &&
                image.bytes.size.toLong() == asset.size
        }?.let { image ->
            log.d { "The image ${release.version} is in memory, no new download" }
            onProgress(asset.size, asset.size)
            return FirmwareImage(release, image.bytes.copyOf())
        }

        log.d { "Download the image ${release.version} from ${asset.url}" }
        val bytes = http.getBytes(asset.url, maxBytes = asset.size, onProgress = onProgress)
        if (bytes.size.toLong() != asset.size) {
            log.w { "The image ${asset.url} has ${bytes.size} bytes, not ${asset.size}" }
            throw FirmwareFeedException.BadSize(asset.url, expected = asset.size, actual = bytes.size.toLong())
        }
        val actualHash = withContext(hashDispatcher) { sha256Hex(bytes) }
        if (actualHash != expectedHash) {
            log.w { "The SHA-256 of the image ${asset.url} is $actualHash, not $expectedHash" }
            throw FirmwareFeedException.BadHash(asset.url, expected = expectedHash, actual = actualHash)
        }
        lastImage = FirmwareImage(release, bytes.copyOf())
        return FirmwareImage(release, bytes)
    }

    /**
     * Removes the last good image from memory, for example after the board accepted it. The next
     * [download] gets the image from the site again.
     */
    suspend fun clearDownload() {
        downloadMutex.withLock { lastImage = null }
    }

    private suspend inline fun <reified T> readOrNull(url: String): T? = try {
        read<T>(url)
    } catch (error: FirmwareFeedException.Http) {
        if (!error.isNotFound) throw error
        log.d { "The firmware site has no file $url" }
        null
    }

    private suspend inline fun <reified T> read(url: String): T {
        val element = parse(url, http.getText(url))
        return try {
            json.decodeFromJsonElement<T>(element)
        } catch (error: IllegalArgumentException) {
            // A SerializationException is an IllegalArgumentException.
            throw FirmwareFeedException.BadJson(url, error.message ?: "the fields do not agree", error)
        }
    }

    private fun parse(url: String, text: String): JsonElement {
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw FirmwareFeedException.BadJson(url, "the file is not JSON", error)
        }
        val schema = ((element as? JsonObject)?.get("schema") as? JsonPrimitive)?.intOrNull
        if (schema != null && schema > FIRMWARE_SITE_SCHEMA) {
            throw FirmwareFeedException.UnsupportedSchema(url, schema)
        }
        return element
    }

    private fun isSiteBoardName(board: String): Boolean = SITE_BOARD_NAME.matches(board).also {
        if (!it) log.w { "The firmware site cannot hold the board name \"$board\"" }
    }

    private companion object {
        val log = Arbor.tag("FirmwareFeed")

        /** The channels of the releases that a beta user gets. */
        val BETA_CHANNELS = setOf(FirmwareChannel.STABLE, FirmwareChannel.BETA)

        /** The board names of the site generator (`_BOARD_RE` in `pages_index.py`). */
        val SITE_BOARD_NAME = Regex("[a-z0-9_]+")

        /** A SHA-256 in lowercase hexadecimal (`_SHA256_RE` in `pages_index.py`). */
        val SHA256_TEXT = Regex("[0-9a-f]{64}")
    }
}
