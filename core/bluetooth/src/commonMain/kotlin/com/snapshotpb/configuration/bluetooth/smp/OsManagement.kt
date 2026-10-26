package com.snapshotpb.configuration.bluetooth.smp

/**
 * The hardware target that a board reports in `os info` with the format `i`. This is a plain value
 * of the SMP package. A model type in `core:model` (work item 8) can take its place later.
 *
 * The board firmware writes `CONFIG_BOARD_TARGET`, for example `snapshot_v2/esp32c3`. A firmware
 * with `CONFIG_MCUMGR_GRP_OS_INFO_HARDWARE_INFO_SHORT_HARDWARE_PLATFORM` (deprecated) writes
 * `CONFIG_BOARD` and `@CONFIG_BOARD_REVISION`: `snapshot_v2` or `snapshot_v2@<revision>`. The parse
 * accepts each of these forms, and also `snapshot_v2@<revision>/esp32c3`.
 *
 * @property board The board name. The image rule of the app compares this name only.
 * @property revision The board revision, or null when the text has none.
 * @property qualifiers The SoC and the other qualifiers after the first `/`, or null.
 */
data class BoardTarget(
    val board: String,
    val revision: String?,
    val qualifiers: String?,
) {
    companion object {
        private const val REVISION_MARK = '@'
        private const val QUALIFIER_MARK = '/'

        /**
         * Parses the `output` of `os info -f i`. Spaces at the two ends are removed, and only the
         * first word counts, because the board puts a space between the answers of several
         * formats. Returns null when the text has no board name.
         */
        fun parse(output: String): BoardTarget? {
            val word = output.trim().substringBefore(' ')
            val path = word.substringBefore(QUALIFIER_MARK)
            val qualifiers = word.substringAfter(QUALIFIER_MARK, missingDelimiterValue = "").ifEmpty { null }
            val board = path.substringBefore(REVISION_MARK)
            val revision = path.substringAfter(REVISION_MARK, missingDelimiterValue = "").ifEmpty { null }
            if (board.isEmpty()) return null
            return BoardTarget(board = board, revision = revision, qualifiers = qualifiers)
        }
    }
}

/**
 * The buffer parameters of the SMP server (`os mcumgr_params`).
 *
 * @property bufferSize `CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE`: the largest request frame, header included.
 * @property bufferCount `CONFIG_MCUMGR_TRANSPORT_NETBUF_COUNT`.
 */
data class McuMgrParams(val bufferSize: Int, val bufferCount: Int)

/** The commands of the OS group (group 0): info, reset and the MCUmgr parameters. */
class OsManagement(private val client: SmpClient) {

    /**
     * Reads `os info` for the [format] letters, for example `i` for the hardware platform. With no
     * [format], the board uses `s`, the kernel name. The board refuses a format with no letter that
     * it knows (`INVALID_FORMAT`).
     */
    suspend fun info(format: String? = null): String =
        client.request(
            SmpOperation.Read, SmpGroup.OS, OsCommand.INFO,
            OsInfoRequestWire.serializer(), OsInfoRequestWire(format),
            OsInfoResponseWire.serializer(),
        ).output

    /**
     * Reads the hardware target of the board (`os info -f i`).
     *
     * @throws SmpMalformedFrame when the answer has no board name.
     */
    suspend fun boardTarget(): BoardTarget {
        val output = info(HARDWARE_PLATFORM_FORMAT)
        return BoardTarget.parse(output)
            ?: throw SmpMalformedFrame("The board reports no hardware platform: \"$output\"")
    }

    /**
     * Asks the board to reset. The board answers first and resets after
     * `CONFIG_MCUMGR_GRP_OS_RESET_MS`, so the link closes soon after this function returns. With
     * [force], the board ignores a refusal of its reset hook.
     */
    suspend fun reset(force: Boolean = false) {
        client.request(
            SmpOperation.Write, SmpGroup.OS, OsCommand.RESET,
            OsResetRequestWire.serializer(), OsResetRequestWire(force = force.takeIf { it }),
            EmptyWire.serializer(),
        )
    }

    /**
     * Reads the buffer parameters of the board. The board has this command only with
     * `CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS`; without it, the answer is the error `ENOTSUP`.
     *
     * @throws SmpMalformedFrame when the answer has no `buf_size` above zero.
     */
    suspend fun mcumgrParams(): McuMgrParams {
        val wire = client.request(
            SmpOperation.Read, SmpGroup.OS, OsCommand.MCUMGR_PARAMS,
            EmptyWire.serializer(), EmptyWire(),
            McuMgrParamsWire.serializer(),
        )
        val bufferSize = wire.bufferSize?.takeIf { it > 0 }
            ?: throw SmpMalformedFrame("The MCUmgr parameters have no buf_size")
        return McuMgrParams(bufferSize = bufferSize, bufferCount = wire.bufferCount ?: 0)
    }

    companion object {
        /** The `os info` format letter of the hardware platform. */
        const val HARDWARE_PLATFORM_FORMAT: String = "i"
    }
}
