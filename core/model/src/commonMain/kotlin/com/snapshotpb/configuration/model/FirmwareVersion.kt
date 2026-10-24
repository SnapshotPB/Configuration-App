package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/** Length of the firmware version field in the advertisement. Firmware `FW_VERSION_ENC_LEN`. */
internal const val FIRMWARE_VERSION_SIZE = 4

/**
 * A board firmware version: `<year>.<week>.<count>`, with `-dev` for a build that is not a release.
 *
 * The firmware version is a calendar version: the ISO week-year, the ISO week, and the number of the
 * release in that week. The advertisement carries it in 4 bytes (`bluetooth.c`, `FW_VERSION_ENC_LEN`):
 * `[year - 2000, week, count, flags]`, where bit 0 of `flags` marks a dev build. The text is the
 * firmware `SNAPSHOT_VERSION_STRING` (`app/CMakeLists.txt`), for example `2026.29.1-dev`. A dev build
 * of the firmware can have a different suffix than `dev`, which the 4 bytes do not carry.
 *
 * The natural order compares the year, then the week, then the count. A dev build is lower than
 * the release with the same numbers.
 */
@Serializable
data class FirmwareVersion(
    val year: Int,
    val week: Int,
    val count: Int,
    val dev: Boolean,
) : Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.year }, { it.week }, { it.count }, { !it.dev })

    /**
     * True when this version is greater than [boardVersion] in the natural order. This is the
     * version rule of an update offer: a release with the same numbers as a dev build on the board
     * is newer, and a release with the same numbers as a release on the board is not.
     */
    fun isNewerThan(boardVersion: FirmwareVersion): Boolean = this > boardVersion

    override fun toString(): String = "$year.$week.$count" + if (dev) "-dev" else ""

    companion object {
        /** Year of the value 0 in the first byte. Firmware: `major = ISO week-year - 2000`. */
        private const val YEAR_BASE = 2000

        /** Bit 0 of the flags byte. Firmware `FW_FLAG_DEV`. */
        private const val FLAG_DEV = 0x01

        /** The largest value of one byte of the advertisement version. */
        private const val BYTE_MAX = 0xFF

        /**
         * The version text: a 4-digit year, the week, the count, and an optional suffix. The tag rule
         * of the firmware release script (`cut_release.py`, `_TAG_RE`) gives the three numbers.
         */
        private val VERSION_TEXT = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,3})(-[0-9A-Za-z.+-]+)?""")

        /** Decodes the 4 version bytes of the advertisement, or returns `null` for another length. */
        fun decode(bytes: ByteArray): FirmwareVersion? {
            if (bytes.size != FIRMWARE_VERSION_SIZE) return null
            return FirmwareVersion(
                year = YEAR_BASE + (bytes[0].toInt() and 0xFF),
                week = bytes[1].toInt() and 0xFF,
                count = bytes[2].toInt() and 0xFF,
                dev = (bytes[3].toInt() and FLAG_DEV) != 0,
            )
        }

        /**
         * Parses a version text, for example the `version` of a release on the firmware site
         * (`2026.43.1`) or the text of a dev build (`2026.29.1-dev`). The year has 4 digits. A
         * suffix after a `-` marks a dev build, whatever its text.
         *
         * Returns `null` for other text, and for numbers that the 4 bytes of the advertisement
         * cannot hold, because the app cannot compare such a version with the version of a board.
         */
        fun parse(text: String): FirmwareVersion? {
            val match = VERSION_TEXT.matchEntire(text.trim()) ?: return null
            val (year, week, count, suffix) = match.destructured
            if (year.toInt() - YEAR_BASE !in 0..BYTE_MAX || count.toInt() > BYTE_MAX) return null
            return FirmwareVersion(
                year = year.toInt(),
                week = week.toInt(),
                count = count.toInt(),
                dev = suffix.isNotEmpty(),
            )
        }
    }
}
