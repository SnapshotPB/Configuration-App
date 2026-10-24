package com.snapshotpb.configuration.bluetooth.smp

import com.toxicbakery.logging.Arbor

/**
 * The SHA-256 hash of an image as MCUboot records it in the image TLVs. This is the hash that
 * `image list` reports and that [ImageManagement.test] and [ImageManagement.confirm] take. It is
 * not the SHA-256 of the whole file: MCUboot does not hash the TLV area.
 */
class ImageHash(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    /** The hash as lowercase hexadecimal text, as the `mcumgr` CLI prints it. */
    fun toHex(): String = bytes.joinToString("") { byte -> (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }

    override fun equals(other: Any?): Boolean = other is ImageHash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ImageHash(${toHex()})"

    private companion object {
        const val BYTE_MASK = 0xFF
        const val HEX_RADIX = 16
    }
}

/**
 * One slot of an image, as `image list` reports it. On a board with one image, slot 0 is the image
 * that runs and slot 1 is the update slot.
 *
 * @property version The image version as text, for example `1.2.3` (`img_mgmt_ver_str()`).
 * @property pending MCUboot swaps to this slot at the next boot.
 * @property confirmed The image stays after the next boot.
 * @property active The image runs now.
 * @property permanent The image of this slot stays after the boot that MCUboot does next.
 */
data class ImageSlot(
    val image: Int,
    val slot: Int,
    val version: String,
    val hash: ImageHash,
    val bootable: Boolean,
    val pending: Boolean,
    val confirmed: Boolean,
    val active: Boolean,
    val permanent: Boolean,
)

/** The progress of an upload: the board has [offset] of [total] bytes. */
data class UploadProgress(val offset: Long, val total: Long) {
    /** The part of the image that the board has, from 0 to 1. */
    val fraction: Float get() = if (total == 0L) 1f else offset.toFloat() / total
}

/**
 * The result of a completed upload. [match] is the answer of the board to its check of the image
 * SHA-256, or null when the board does not do the check (`CONFIG_IMG_ENABLE_IMAGE_CHECK` off).
 */
data class UploadResult(val match: Boolean?)

/**
 * The commands of the image group (group 1): image state, upload, test, confirm and erase.
 */
class ImageManagement(private val client: SmpClient) {

    /** Reads the state of each image slot (`image list`). */
    suspend fun list(): List<ImageSlot> =
        client.request(
            SmpOperation.Read, SmpGroup.IMAGE, ImageCommand.STATE,
            EmptyWire.serializer(), EmptyWire(),
            ImageStateWire.serializer(),
        ).toSlots()

    /**
     * Marks the image with [hash] as pending, without confirm: MCUboot boots it at the next reset.
     * Get [hash] from [list] after the upload. Returns the new slot state.
     *
     * Whether MCUboot can go back to the old image depends on the bootloader mode. The bootloader of
     * the board is overwrite-only now: it copies the image over slot 0, so `test` and [confirm] both
     * make the image permanent, and there is no revert.
     */
    suspend fun test(hash: ImageHash): List<ImageSlot> =
        client.request(
            SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE,
            ImageStateWriteWire.serializer(), ImageStateWriteWire(hash = hash.toByteArray(), confirm = false),
            ImageStateWire.serializer(),
        ).toSlots()

    /**
     * Confirms an image, so that MCUboot keeps it. With no [hash], the board confirms the image that
     * runs now. With a [hash] of the update slot, MCUboot boots that image at the next reset and
     * keeps it. Returns the new slot state.
     */
    suspend fun confirm(hash: ImageHash? = null): List<ImageSlot> =
        client.request(
            SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE,
            ImageStateWriteWire.serializer(), ImageStateWriteWire(hash = hash?.toByteArray(), confirm = true),
            ImageStateWire.serializer(),
        ).toSlots()

    /**
     * Erases an image slot. With no [slot], the board erases the slot that does not run. The board
     * refuses a slot that is pending or confirmed (`NO_FREE_SLOT`).
     */
    suspend fun erase(slot: Int? = null) {
        client.request(
            SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.ERASE,
            ImageEraseWire.serializer(), ImageEraseWire(slot),
            EmptyWire.serializer(),
            timeoutMillis = ERASE_TIMEOUT_MILLIS,
        )
    }

    /**
     * Uploads [image] to the update slot.
     *
     * The loop follows img_mgmt. Each request carries the data from one offset. The answer gives
     * the offset that the board expects next, and the next request starts at that offset, also when
     * it is lower than the end of the last request. A request at offset 0 also carries `len`, `sha`
     * and `image`.
     *
     * To resume after a lost link, call this function again with the same [image] and [sha256]. The
     * first request at offset 0 then has the same `sha` and `len`, and a board that still holds the
     * upload drops the data of that request and answers with its stored offset
     * (`img_mgmt_upload_inspect()`). The loop continues from there. A board that restarted, or that
     * refused a chunk, has no upload in progress: it takes the request as a new upload, and the
     * upload starts again at 0. `image list` does not report the offset of an upload in progress,
     * so this first request is the only way to get it. There is no start offset: a request at a
     * later offset has no `sha`, so a board that holds an upload of a different image at that
     * offset would add the data to the wrong image.
     *
     * The frame size (header and CBOR body) of each request:
     *
     * - With a [boardBufferSize] from [OsManagement.mcumgrParams], each request fills a frame of that
     *   size, and never more. The client splits each frame into writes of [SmpClient.maxWriteLength]
     *   bytes, which the board joins (`CONFIG_MCUMGR_TRANSPORT_BT_REASSEMBLY`). The board firmware
     *   has 2475 bytes, so at the MTU 498 one request is five writes of 495 bytes, and the client
     *   waits for one answer in place of five. A board that answers `mcumgr_params` is taken to
     *   join writes, as the Nordic clients do. The board firmware has both (`app/ota.conf`).
     * - With no [boardBufferSize], the frame is the safe default of [DEFAULT_BOARD_BUFFER_SIZE]
     *   bytes. It is one write when one write can carry the 32-byte image header in the first chunk,
     *   so that a board without reassembly works. On a link with a smaller MTU, each request has
     *   [DEFAULT_BOARD_BUFFER_SIZE] bytes in several writes.
     *
     * @param sha256 The SHA-256 of the whole [image] file, 32 bytes.
     * @param boardBufferSize The `buf_size` of [OsManagement.mcumgrParams], or null when the board
     *   does not answer that command.
     * @param onProgress Called after each answer with the offset of the board.
     * @throws SmpGroupError when the board refuses a chunk, for example `NO_FREE_SLOT`.
     * @throws SmpUploadStalled when the board does not move its offset, or when the client sent
     *   three times the image size and the board does not have the full image.
     * @throws SmpImageMismatch when the board reports that the data does not have [sha256].
     * @throws IllegalArgumentException when [boardBufferSize] cannot carry the image header.
     */
    suspend fun upload(
        image: ByteArray,
        sha256: ByteArray,
        boardBufferSize: Int? = null,
        onProgress: (UploadProgress) -> Unit = {},
    ): UploadResult {
        require(image.isNotEmpty()) { "The image is empty" }
        require(sha256.size == SHA256_LENGTH) { "The SHA-256 has ${sha256.size} bytes, not $SHA256_LENGTH" }
        val total = image.size.toLong()

        val frameBudget = uploadFrameBudget(client.maxWriteLength, boardBufferSize, total, sha256)
        log.d { "Upload of $total bytes with frames of up to $frameBudget bytes" }
        var offset = 0L
        var idleAnswers = 0
        var sentBytes = 0L
        while (true) {
            if (sentBytes > total * MAX_SENT_FACTOR) throw SmpUploadStalled(offset, offset)
            val length = minOf(uploadChunkLength(frameBudget, offset, total, sha256).toLong(), total - offset).toInt()
            sentBytes += length
            val chunk = image.copyOfRange(offset.toInt(), offset.toInt() + length)
            val answer = client.request(
                SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.UPLOAD,
                ImageUploadRequestWire.serializer(), uploadRequest(chunk, offset, total, sha256),
                ImageUploadResponseWire.serializer(),
                timeoutMillis = if (offset == 0L) FIRST_CHUNK_TIMEOUT_MILLIS else DEFAULT_TIMEOUT,
            )
            val next = answer.off ?: throw SmpMalformedFrame("The upload answer has no off")
            if (next !in 0..total) throw SmpMalformedFrame("The upload answer has off $next, out of 0..$total")
            if (next > offset) {
                idleAnswers = 0
            } else {
                // Lower: the board lost the upload or wants data again. Same: it did not accept it.
                idleAnswers++
                log.d { "The board answers off $next to the chunk at $offset" }
                if (idleAnswers > MAX_IDLE_ANSWERS) throw SmpUploadStalled(offset, next)
            }
            offset = next
            onProgress(UploadProgress(offset, total))
            if (offset == total) {
                if (answer.match == false) throw SmpImageMismatch()
                return UploadResult(answer.match)
            }
        }
    }

    companion object {
        /**
         * The Zephyr default of `CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE`. A request frame, header
         * included, must fit in one such buffer: the board drops a longer frame with no answer
         * (`smp_reassembly_collect()`), and the request then times out. This value is safe for each
         * firmware. The board firmware sets 2475: give the `bufferSize` of
         * [OsManagement.mcumgrParams] to [upload], so that the upload uses the large buffer.
         */
        const val DEFAULT_BOARD_BUFFER_SIZE: Int = 384

        /** The largest frame that the 16-bit length of the SMP header permits. */
        private const val MAX_FRAME_SIZE: Int = SmpHeader.SIZE + 0xFFFF

        /** The size of the MCUmgr image header. img_mgmt refuses a first chunk with fewer bytes. */
        const val IMAGE_HEADER_LENGTH: Int = 32

        const val SHA256_LENGTH: Int = 32

        /**
         * The time limit for an erase, and for the first chunk, which can erase the slot when
         * `CONFIG_IMG_ERASE_PROGRESSIVELY` is off. The erase time of the board is not measured yet.
         */
        const val ERASE_TIMEOUT_MILLIS: Long = 30_000L

        private const val FIRST_CHUNK_TIMEOUT_MILLIS: Long = ERASE_TIMEOUT_MILLIS
        private const val DEFAULT_TIMEOUT: Long = SmpClient.DEFAULT_RESPONSE_TIMEOUT_MILLIS

        /** Answers in sequence that do not move the offset before the upload stops. */
        private const val MAX_IDLE_ANSWERS = 4

        /**
         * The upload stops when the client sent this many times the image size. This ends a loop
         * where the board moves its offset forward and back again and again.
         */
        private const val MAX_SENT_FACTOR = 3

        private val log = Arbor.tag("ImageManagement")

        /**
         * The body of the upload request at [offset]. The request at offset 0 carries the fields
         * that img_mgmt needs to start or to resume an upload.
         */
        internal fun uploadRequest(data: ByteArray, offset: Long, imageSize: Long, sha256: ByteArray) =
            if (offset == 0L) {
                ImageUploadRequestWire(image = 0, len = imageSize, off = 0L, sha = sha256, data = data)
            } else {
                ImageUploadRequestWire(off = offset, data = data)
            }

        /**
         * The largest number of image bytes that one upload request at [offset] can carry in a frame
         * of [frameBudget] bytes. The frame is the 8-byte header plus the CBOR map. The map
         * overhead comes from the encoder itself: it is the size of the request with no data, minus
         * the 1-byte head of an empty byte string. The data then needs its bytes plus its own head.
         * The result is -1 when the frame cannot hold the request with no data.
         */
        internal fun uploadChunkLength(frameBudget: Int, offset: Long, imageSize: Long, sha256: ByteArray): Int {
            val empty = uploadRequest(ByteArray(0), offset, imageSize, sha256)
            val overhead = SmpHeader.SIZE +
                SmpCbor.encode(ImageUploadRequestWire.serializer(), empty).size -
                SmpCbor.headLength(0)
            return largestByteStringLength(frameBudget - overhead)
        }

        /**
         * The frame size of an upload (see [upload]). With a known [boardBufferSize], it is that
         * size. With none, it is one ATT write of up to [DEFAULT_BOARD_BUFFER_SIZE] bytes when that
         * write can carry the image header in the first chunk, else [DEFAULT_BOARD_BUFFER_SIZE].
         *
         * @throws IllegalArgumentException when [boardBufferSize] cannot carry the image header.
         */
        internal fun uploadFrameBudget(maxWriteLength: Int, boardBufferSize: Int?, imageSize: Long, sha256: ByteArray): Int {
            val needed = minOf(IMAGE_HEADER_LENGTH.toLong(), imageSize).toInt()
            fun carriesHeader(frame: Int) = uploadChunkLength(frame, 0L, imageSize, sha256) >= needed
            if (boardBufferSize != null) {
                val frame = minOf(boardBufferSize, MAX_FRAME_SIZE)
                require(carriesHeader(frame)) {
                    "A board buffer of $boardBufferSize bytes cannot carry the first chunk of an upload"
                }
                return frame
            }
            val singleWrite = minOf(maxWriteLength, DEFAULT_BOARD_BUFFER_SIZE)
            return if (carriesHeader(singleWrite)) singleWrite else DEFAULT_BOARD_BUFFER_SIZE
        }

        /**
         * The largest n that has n + head(n) <= [room], where head(n) is the size of the CBOR head of
         * a byte string of n bytes. The heads in increasing size give the values of n in decreasing
         * order, so the first value that fits its head is the largest. The result is -1 when [room]
         * is less than 1, because an empty byte string needs its 1-byte head.
         */
        private fun largestByteStringLength(room: Int): Int {
            for (head in intArrayOf(1, 2, 3, 5)) {
                val length = room - head
                if (length >= 0 && SmpCbor.headLength(length.toLong()) <= head) return length
            }
            return -1
        }

        private fun ImageStateWire.toSlots(): List<ImageSlot> = images.map { wire ->
            ImageSlot(
                image = wire.image,
                slot = wire.slot,
                version = wire.version,
                hash = ImageHash(wire.hash),
                bootable = wire.bootable,
                pending = wire.pending,
                confirmed = wire.confirmed,
                active = wire.active,
                permanent = wire.permanent,
            )
        }
    }
}
