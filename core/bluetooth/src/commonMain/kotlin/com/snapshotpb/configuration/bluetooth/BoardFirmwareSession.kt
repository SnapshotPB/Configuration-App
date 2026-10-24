package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.smp.ImageHash
import com.snapshotpb.configuration.bluetooth.smp.ImageManagement
import com.snapshotpb.configuration.bluetooth.smp.ImageSlot
import com.snapshotpb.configuration.bluetooth.smp.McuMgrError
import com.snapshotpb.configuration.bluetooth.smp.McubootImage
import com.snapshotpb.configuration.bluetooth.smp.OsManagement
import com.snapshotpb.configuration.bluetooth.smp.SmpCharacteristicMissing
import com.snapshotpb.configuration.bluetooth.smp.SmpClient
import com.snapshotpb.configuration.bluetooth.smp.SmpException
import com.snapshotpb.configuration.bluetooth.smp.SmpGroupError
import com.snapshotpb.configuration.bluetooth.smp.SmpImageMismatch
import com.snapshotpb.configuration.bluetooth.smp.SmpMalformedFrame
import com.snapshotpb.configuration.bluetooth.smp.SmpReturnCodeError
import com.snapshotpb.configuration.bluetooth.smp.SmpTimeout
import com.snapshotpb.configuration.bluetooth.smp.SmpTransport
import com.snapshotpb.configuration.bluetooth.smp.SmpTransportFailure
import com.snapshotpb.configuration.bluetooth.smp.SmpUploadStalled
import com.snapshotpb.configuration.bluetooth.smp.SmpWriteUnsupported
import com.snapshotpb.configuration.bluetooth.smp.UploadProgress
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.HardwareTarget
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException

/**
 * The SMP link of one connected board, and the steps of a firmware update on it. The engine makes
 * one session for each link after the 4-step connect, and closes it with the link.
 *
 * The session does no locking. The engine runs each step while it holds its write lock, so no OTS
 * write and no other SMP step runs at the same time.
 *
 * @param unsubscribe Removes the notification subscription of the transport.
 */
internal class BoardFirmwareSession(
    transport: SmpTransport,
    private val unsubscribe: suspend () -> Unit,
) {
    private val client = SmpClient(transport)
    private val images = ImageManagement(client)
    private val os = OsManagement(client)

    /** The last result of [probe], updated by the later steps. */
    var service: BoardUpdateService = BoardUpdateService(UpdateServiceStatus.CHECKING)
        private set

    /**
     * Reads the hardware target (`os info -f i`), the buffer size (`os mcumgr_params`) and the
     * image that runs (`image list`). Only the hardware target is necessary: a board that does not
     * answer the other two commands still gets [UpdateServiceStatus.AVAILABLE].
     *
     * @throws SmpWriteUnsupported when the engine of the platform has no typed write.
     * @throws SmpException when the board does not answer `os info`.
     */
    suspend fun probe(): BoardUpdateService {
        val boardTarget = os.boardTarget()
        val target = HardwareTarget(
            board = boardTarget.board,
            revision = boardTarget.revision,
            qualifiers = boardTarget.qualifiers,
        )
        val bufferSize = optional("os mcumgr_params") { os.mcumgrParams().bufferSize }
        val activeImage = optional("image list") { images.list().activeImage() }
        service = BoardUpdateService(UpdateServiceStatus.AVAILABLE, target, bufferSize, activeImage)
        return service
    }

    /**
     * Uploads [image] to slot 1, marks it for a test boot and resets the board. Returns the MCUboot
     * hash of the uploaded image.
     *
     * The first `image list` tells whether slot 1 already holds [image], from an earlier attempt:
     * its hash is the MCUboot hash in the TLVs of [image] ([McubootImage.hashOf]). When the slot is
     * pending, only the reset follows. When it is not pending, `image test` and the reset follow.
     * An earlier attempt can stop after the upload or after `image test`, for example when the link
     * drops before the reset; a new upload to a pending slot would then fail. Else the upload runs.
     *
     * @param sha256 The SHA-256 of the whole image file, 32 bytes.
     * @param onProgress Receives the offset of the board after each chunk.
     * @param onResetting Called after the upload, before `image test`.
     * @throws SmpException when a command fails.
     * @throws FirmwareStepRefused when the board does not list or mark the uploaded image.
     */
    suspend fun upload(
        image: ByteArray,
        sha256: ByteArray,
        onProgress: (UploadProgress) -> Unit,
        onResetting: () -> Unit,
    ): ImageHash {
        val before = images.list()
        log.d { "Slots before the upload: ${before.describe()}" }
        val imageHash = McubootImage.hashOf(image)
        val held = before.updateSlot()?.takeIf { slot -> imageHash != null && slot.hash == imageHash && !slot.active }
        if (held != null) {
            log.d { "Slot $UPDATE_SLOT holds the image ${held.hash.toHex()} already (pending: ${held.pending}); no upload" }
            onProgress(UploadProgress(image.size.toLong(), image.size.toLong()))
            onResetting()
            if (!held.pending) testImage(held.hash)
            os.reset()
            return held.hash
        }
        images.upload(image, sha256, boardBufferSize = service.bufferSize, onProgress = onProgress)
        val after = images.list()
        log.d { "Slots after the upload: ${after.describe()}" }
        val uploaded = after.updateSlot()
            ?: throw FirmwareStepRefused("The board lists no image in slot $UPDATE_SLOT after the upload")
        onResetting()
        testImage(uploaded.hash)
        os.reset()
        log.d { "The board resets to the image ${uploaded.hash.toHex()}" }
        return uploaded.hash
    }

    /** Marks the image with [hash] for a test boot, and checks that the board marked it pending. */
    private suspend fun testImage(hash: ImageHash) {
        val tested = images.test(hash)
        if (tested.none { it.hash == hash && it.pending }) {
            throw FirmwareStepRefused("The board did not mark the image ${hash.toHex()} as pending")
        }
    }

    /** The update slot of a board with one image. */
    private fun List<ImageSlot>.updateSlot(): ImageSlot? = firstOrNull { it.image == 0 && it.slot == UPDATE_SLOT }

    /**
     * Confirms the image that runs when `image list` shows it as not confirmed, and returns it. The
     * firmware can confirm itself, so an image that is confirmed gets no command.
     *
     * @throws FirmwareStepRefused when the board lists no active image, or keeps it unconfirmed.
     */
    suspend fun confirmActiveImage(): BoardImage {
        val active = images.list().firstOrNull { it.active }
            ?: throw FirmwareStepRefused("The board lists no active image")
        val confirmed = if (active.confirmed) {
            log.d { "The board confirmed the image ${active.hash.toHex()} itself" }
            active
        } else {
            log.d { "Confirm the image ${active.hash.toHex()}" }
            images.confirm().firstOrNull { it.active }
                ?.takeIf { it.confirmed }
                ?: throw FirmwareStepRefused("The board did not confirm the image ${active.hash.toHex()}")
        }
        return confirmed.toBoardImage().also { image -> service = service.copy(activeImage = image) }
    }

    /** Removes the notification subscription. A failure is logged only: the link can be closed. */
    suspend fun close() {
        try {
            unsubscribe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.d { "The SMP session did not close: ${error.message}" }
        }
    }

    /** Runs [read], and gives `null` with a log line when the board refuses it. */
    private inline fun <T> optional(command: String, read: () -> T): T? = try {
        read()
    } catch (error: SmpGroupError) {
        log.d { "The board refuses $command: ${error.message}" }
        null
    } catch (error: SmpReturnCodeError) {
        log.d { "The board refuses $command: ${error.message}" }
        null
    } catch (error: SmpMalformedFrame) {
        log.d { "The answer to $command is not valid: ${error.message}" }
        null
    }

    private fun List<ImageSlot>.activeImage(): BoardImage? = firstOrNull { it.active }?.toBoardImage()

    private fun ImageSlot.toBoardImage() = BoardImage(version = version, hash = hash.toHex(), confirmed = confirmed)

    private fun List<ImageSlot>.describe(): String = joinToString { slot ->
        "slot ${slot.slot} ${slot.version} active=${slot.active} confirmed=${slot.confirmed} pending=${slot.pending}"
    }

    private companion object {
        /** The update slot of a board with one image. */
        const val UPDATE_SLOT = 1
        val log = Arbor.tag("BoardFirmwareSession")
    }
}

/** The board did not do a step of the update, but it answered each command with no error. */
internal class FirmwareStepRefused(message: String) : Exception(message)

/**
 * The failure reason of an update step that threw [error]. [linkLive] tells whether the link was
 * still up after the failure: an unknown error on a closed link is a lost link.
 */
internal fun firmwareFailureOf(error: Throwable, linkLive: Boolean): FirmwareUpdateFailure = when (error) {
    is SmpWriteUnsupported, is SmpCharacteristicMissing -> FirmwareUpdateFailure.NO_UPDATE_SERVICE
    is SmpTransportFailure -> FirmwareUpdateFailure.LINK_LOST
    // A board that does not answer on a live link (for example, a slow flash erase) did not lose the link.
    is SmpTimeout -> if (linkLive) FirmwareUpdateFailure.BOARD_REFUSED else FirmwareUpdateFailure.LINK_LOST
    is SmpGroupError, is SmpReturnCodeError, is SmpUploadStalled, is SmpImageMismatch,
    is SmpMalformedFrame, is FirmwareStepRefused -> FirmwareUpdateFailure.BOARD_REFUSED
    else -> if (linkLive) FirmwareUpdateFailure.BOARD_REFUSED else FirmwareUpdateFailure.LINK_LOST
}

/**
 * A short code for the `detail` of a failed step: the class name, and the code of an SMP error. It
 * holds no message text, so a screen can show it as a reference.
 */
internal fun firmwareFailureCode(error: Throwable): String {
    val name = error::class.simpleName ?: "Unknown"
    return when (error) {
        is SmpGroupError -> "$name(${error.imageError?.name ?: error.osError?.name ?: "group ${error.errorGroup} rc ${error.rc}"})"
        is SmpReturnCodeError -> "$name(${McuMgrError.fromCode(error.rc)?.name ?: "rc ${error.rc}"})"
        else -> name
    }
}
