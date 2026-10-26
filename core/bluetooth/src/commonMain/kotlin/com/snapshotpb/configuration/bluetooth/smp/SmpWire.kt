package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

// The CBOR bodies of the SMP requests and responses that this client sends and reads. The key names
// and types come from the Zephyr 4.4 server code (img_mgmt.c, img_mgmt_state.c, os_mgmt.c, smp.c)
// and from smp_group_0.rst and smp_group_1.rst. The encoder writes the properties in declaration
// order. zcbor_map_decode_bulk() on the board accepts the keys in any order.

/** An empty map. Each request has a map body, also when the command reads no field. */
@Serializable
internal class EmptyWire

/** The two error forms of a response. A response class lists neither, so each body is read two times. */
@Serializable
internal class SmpStatusWire(
    val rc: Int? = null,
    val err: SmpErrWire? = null,
)

/** The `err` map of SMP v2. A code of zero is not an error. */
@Serializable
internal class SmpErrWire(
    val group: Int = 0,
    val rc: Int = 0,
)

/** Image state write: `test` sends [confirm] false and a [hash], `confirm` sends true. */
@Serializable
internal class ImageStateWriteWire(
    @ByteString val hash: ByteArray? = null,
    val confirm: Boolean,
)

/** Image state read (and the answer of a state write). */
@Serializable
internal class ImageStateWire(
    val images: List<ImageSlotWire> = emptyList(),
)

/**
 * One slot of an image state answer. A server with `CONFIG_MCUMGR_GRP_IMG_FRUGAL_LIST` writes a
 * flag only when it is true, and it writes `image` only when it has more than one image.
 */
@Serializable
internal class ImageSlotWire(
    val image: Int = 0,
    val slot: Int = 0,
    val version: String = "",
    @ByteString val hash: ByteArray = ByteArray(0),
    val bootable: Boolean = false,
    val pending: Boolean = false,
    val confirmed: Boolean = false,
    val active: Boolean = false,
    val permanent: Boolean = false,
)

/**
 * Image upload. A request at offset 0 must have [len], and this client also sends [image] and
 * [sha]: img_mgmt resumes an upload when [sha] is the same as the [sha] of the upload in progress.
 * A request at a later offset has only [off] and [data].
 */
@Serializable
internal class ImageUploadRequestWire(
    val image: Int? = null,
    val len: Long? = null,
    val off: Long,
    @ByteString val sha: ByteArray? = null,
    @ByteString val data: ByteArray,
)

/** Image upload answer: the offset that the board expects next, and `match` with the last chunk. */
@Serializable
internal class ImageUploadResponseWire(
    val off: Long? = null,
    val match: Boolean? = null,
)

/** Image erase. With no [slot], the board erases the slot that does not run. */
@Serializable
internal class ImageEraseWire(
    val slot: Int? = null,
)

/** OS info request. With no [format], the board uses `s` (the kernel name). */
@Serializable
internal class OsInfoRequestWire(
    val format: String? = null,
)

/** OS info answer. */
@Serializable
internal class OsInfoResponseWire(
    val output: String = "",
)

/** OS reset. A [force] reset ignores a refusal of the reset hook. */
@Serializable
internal class OsResetRequestWire(
    val force: Boolean? = null,
)

/** MCUmgr parameters answer (`CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS`). */
@Serializable
internal class McuMgrParamsWire(
    @SerialName("buf_size") val bufferSize: Int? = null,
    @SerialName("buf_count") val bufferCount: Int? = null,
)
