package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An [SmpTransport] for tests. It records each write, joins the pieces into request frames, and
 * gives each frame to [onRequest]. The pieces that [onRequest] returns go out as notifications, in
 * order. A test can also send a notification of its own with [notify].
 *
 * [boardBufferSize] is `CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE` of the modelled board. A frame that is
 * longer fails the test: the real board drops such a frame with no answer.
 */
internal class FakeSmpTransport(
    override var maxWriteLength: Int = maxWriteLengthForMtu(247),
    var boardBufferSize: Int = ImageManagement.DEFAULT_BOARD_BUFFER_SIZE,
) : SmpTransport {
    private val emitter = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    private val assembler = SmpFrameAssembler(responses = false)

    override val notifications: Flow<ByteArray> get() = emitter

    /** Each value given to [write], in order. */
    val writes: MutableList<ByteArray> = mutableListOf()

    /** Each complete request frame, in order. */
    val requests: MutableList<SmpFrame> = mutableListOf()

    /** The answer to a request frame: the notification pieces. An empty list sends no answer. */
    var onRequest: suspend (SmpFrame) -> List<ByteArray> = { emptyList() }

    /** When set, [write] throws it. */
    var writeFailure: Throwable? = null

    override suspend fun write(value: ByteArray) {
        check(value.size <= maxWriteLength) { "A write of ${value.size} bytes is over the limit of $maxWriteLength" }
        writeFailure?.let { throw it }
        writes += value
        assembler.append(value).forEach { frame ->
            check(frame.encode().size <= boardBufferSize) {
                "A frame of ${frame.encode().size} bytes is over the board buffer of $boardBufferSize bytes"
            }
            requests += frame
            onRequest(frame).forEach { piece -> emitter.emit(piece) }
        }
    }

    suspend fun notify(piece: ByteArray) {
        emitter.emit(piece)
    }
}

/** The response frame to [request] with [body], in one piece. */
internal fun responseTo(request: SmpFrame, body: ByteArray, version: SmpVersion = SmpVersion.V2): ByteArray =
    SmpFrame(
        SmpHeader(
            operation = request.header.operation.response,
            version = version,
            length = body.size,
            group = request.header.group,
            sequence = request.header.sequence,
            command = request.header.command,
        ),
        body,
    ).encode()

/** [frame] with the sequence number of [request]. A fixture has a fixed sequence number. */
internal fun ByteArray.withSequenceOf(request: SmpFrame): ByteArray =
    copyOf().also { it[6] = request.header.sequence.toByte() }

/** Splits [bytes] into pieces of [size] bytes, as the board splits a response at `MTU - 3`. */
internal fun ByteArray.pieces(size: Int): List<ByteArray> =
    (indices step size).map { start -> copyOfRange(start, minOf(start + size, this.size)) }

/**
 * A model of the upload logic of img_mgmt (`img_mgmt_upload()`, `img_mgmt_upload_inspect()`).
 *
 * A request at offset 0 starts an upload, or resumes the upload in progress when it has the same
 * `sha`. A request at another offset continues the upload only at the offset that the board
 * expects; else the board drops the data and answers with its offset. The last chunk gets `match`.
 *
 * [refuseAt] makes the board refuse the chunk at that offset with an image group error. [loseStateAt]
 * makes the board forget the upload once, when a chunk reaches that offset, as after a restart.
 * [checkImage] models `CONFIG_IMG_ENABLE_IMAGE_CHECK`, which adds `match` to the last answer. The
 * board firmware does not set it, so the default is false.
 */
internal class FakeImageBoard(
    private val refuseAt: Long? = null,
    private val refusal: ImageManagementError = ImageManagementError.NO_FREE_SLOT,
    private var loseStateAt: Long? = null,
    private val checkImage: Boolean = false,
) {
    private var size = -1L
    private var offset = 0L
    private var sha: ByteArray? = null
    private var slot = ByteArray(0)

    /** The offset of each upload request, in order. */
    val requestOffsets: MutableList<Long> = mutableListOf()

    /** The offset of each request that carried `len` and `sha`. */
    val firstChunkOffsets: MutableList<Long> = mutableListOf()

    /** The data in the update slot. */
    val written: ByteArray get() = slot.copyOf()

    fun answer(request: SmpFrame): List<ByteArray> {
        check(request.header.group == SmpGroup.IMAGE && request.header.command == ImageCommand.UPLOAD)
        val upload = SmpCbor.decode(ImageUploadRequestWire.serializer(), request.body)
        requestOffsets += upload.off
        if (upload.len != null) firstChunkOffsets += upload.off
        if (upload.off == refuseAt) {
            return listOf(responseTo(request, SmpCbor.encode(SmpStatusWire.serializer(), SmpStatusWire(err = SmpErrWire(SmpGroup.IMAGE, refusal.code)))))
        }
        if (upload.off == 0L) {
            val len = checkNotNull(upload.len) { "The first chunk has no len" }
            check(upload.data.size >= ImageManagement.IMAGE_HEADER_LENGTH || upload.data.size.toLong() == len) {
                "The first chunk has ${upload.data.size} bytes, fewer than the image header"
            }
            val resume = size >= 0 && sha?.contentEquals(upload.sha) == true
            if (resume) return listOf(offsetAnswer(request, match = null))
            size = len
            offset = 0L
            sha = upload.sha
            slot = ByteArray(len.toInt())
        } else if (size < 0 || upload.off != offset) {
            return listOf(offsetAnswer(request, match = null))
        }
        upload.data.copyInto(slot, destinationOffset = offset.toInt())
        offset += upload.data.size
        val done = offset == size
        val answer = offsetAnswer(request, match = if (done && checkImage) true else null)
        val lostAt = loseStateAt
        if (lostAt != null && offset >= lostAt) {
            // The board restarts after this answer. The slot keeps its data, but the upload state goes.
            loseStateAt = null
            size = -1L
            offset = 0L
            sha = null
        }
        return listOf(answer)
    }

    private fun offsetAnswer(request: SmpFrame, match: Boolean?): ByteArray =
        responseTo(request, SmpCbor.encode(ImageUploadResponseWire.serializer(), ImageUploadResponseWire(off = offset, match = match)))
}
