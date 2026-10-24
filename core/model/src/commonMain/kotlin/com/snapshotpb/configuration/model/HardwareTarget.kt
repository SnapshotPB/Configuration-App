package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The hardware target that a board reports: the Zephyr `CONFIG_BOARD_TARGET` of its firmware.
 *
 * The board gives the target in the answer to the MCUmgr command `os info` with the format `i`,
 * for example `snapshot_v2/esp32c3`. The Zephyr form is `<board>[@<revision>][/<qualifiers>]`.
 * [board] is the part before the first `/`, without a revision. The firmware site names the
 * boards of a release with this name (`compatible_hardware`).
 *
 * The app keeps the target of a connected board in memory only. It is not in the device file, so
 * it is not a synced field.
 */
@Serializable
data class HardwareTarget(
    val board: String,
    val revision: String? = null,
    val qualifiers: String? = null,
) {
    /** The target in the Zephyr form, for example `snapshot_v2/esp32c3`. */
    override fun toString(): String =
        board + revision?.let { "@$it" }.orEmpty() + qualifiers?.let { "/$it" }.orEmpty()

    companion object {
        /**
         * Parses the answer of `os info -f i`. Returns `null` when the text has no board name, or
         * when the board name has a space in it.
         */
        fun parse(text: String): HardwareTarget? {
            val target = text.trim()
            val head = target.substringBefore('/')
            val qualifiers = if ('/' in target) target.substringAfter('/') else null
            val board = head.substringBefore('@')
            val revision = if ('@' in head) head.substringAfter('@') else null
            if (board.isEmpty() || board.any { it.isWhitespace() }) return null
            return HardwareTarget(
                board = board,
                revision = revision?.takeIf { it.isNotEmpty() },
                qualifiers = qualifiers?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
