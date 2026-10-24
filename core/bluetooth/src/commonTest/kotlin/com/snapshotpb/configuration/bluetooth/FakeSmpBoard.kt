package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.smp.EmptyWire
import com.snapshotpb.configuration.bluetooth.smp.FakeImageBoard
import com.snapshotpb.configuration.bluetooth.smp.ImageCommand
import com.snapshotpb.configuration.bluetooth.smp.ImageSlotWire
import com.snapshotpb.configuration.bluetooth.smp.ImageStateWire
import com.snapshotpb.configuration.bluetooth.smp.ImageStateWriteWire
import com.snapshotpb.configuration.bluetooth.smp.McuMgrParamsWire
import com.snapshotpb.configuration.bluetooth.smp.OsCommand
import com.snapshotpb.configuration.bluetooth.smp.OsInfoResponseWire
import com.snapshotpb.configuration.bluetooth.smp.SmpCbor
import com.snapshotpb.configuration.bluetooth.smp.SmpErrWire
import com.snapshotpb.configuration.bluetooth.smp.SmpFrame
import com.snapshotpb.configuration.bluetooth.smp.SmpFrameAssembler
import com.snapshotpb.configuration.bluetooth.smp.SmpGroup
import com.snapshotpb.configuration.bluetooth.smp.SmpOperation
import com.snapshotpb.configuration.bluetooth.smp.SmpStatusWire
import com.snapshotpb.configuration.bluetooth.smp.SmpUuids
import com.snapshotpb.configuration.bluetooth.smp.responseTo
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerializationStrategy

/**
 * A GATT-level SMP server for engine tests, on [FakeBlueFalconEngine]. It joins the typed writes to
 * the SMP characteristic into request frames and answers `os info`, `os mcumgr_params`, `os reset`,
 * `image list`, `image test`, `image confirm` and the upload ([FakeImageBoard]) as the board firmware
 * does. Give [service] to the peripheral of [FakeOtsBoard].
 */
internal class FakeSmpBoard(
    /** The answer to `os info -f i`. */
    var target: String = "snapshot_v2/esp32c3",
) {
    val characteristic = FakeOtsCharacteristic(SmpUuids.Characteristic)
    val service: BluetoothService = FakeBluetoothService(SmpUuids.Service, characteristics = listOf(characteristic))

    private val assembler = SmpFrameAssembler(responses = false)

    /** The upload model. Replace it before the upload to make the board refuse a chunk. */
    var imageBoard = FakeImageBoard()

    /** Each request frame, in order. */
    val requests: MutableList<SmpFrame> = mutableListOf()

    /** The group and the command of each request, in order. */
    val commands: List<Pair<Int, Int>> get() = requests.map { it.header.group to it.header.command }

    /** When set, `os info` gets this image group error in place of an answer. */
    var refuseOsInfo: Boolean = false

    /** When set, `os mcumgr_params` gets `ENOTSUP`, as on a board without the command. */
    var refuseParams: Boolean = false

    /** When set, `image test` answers with the slot state but does not mark the slot pending. */
    var ignoreTest: Boolean = false

    /** When set, `os info` waits for this gate before the board answers. */
    var osInfoGate: CompletableDeferred<Unit>? = null

    /** When set, each upload request waits for this gate before the board answers. */
    var uploadGate: CompletableDeferred<Unit>? = null

    /** When set, each write gets this result and reaches no board, as after a lost link. */
    var writeResult: CharacteristicWriteResult? = null

    /** The image that runs. */
    var activeHash: ByteArray = OLD_HASH
    var activeConfirmed: Boolean = true
    private var uploadedHash: ByteArray? = null
    private var pending = false

    /** True after `os reset`. */
    var resetCount: Int = 0
        private set

    /** Answers the typed writes of [engine]. The OTS board uses the untyped writes. */
    fun install(engine: FakeBlueFalconEngine) {
        engine.onTypedWrite = { characteristic, value, type ->
            when {
                characteristic !== this.characteristic -> CharacteristicWriteResult.Unsupported
                type != CharacteristicWriteType.WithoutResponse -> CharacteristicWriteResult.Unsupported
                writeResult != null -> writeResult!!
                else -> {
                    assembler.append(value).forEach { frame ->
                        requests += frame
                        answer(frame).forEach { piece -> this.characteristic.emitter.emit(piece) }
                    }
                    CharacteristicWriteResult.Sent
                }
            }
        }
    }

    private suspend fun answer(frame: SmpFrame): List<ByteArray> {
        val group = frame.header.group
        val command = frame.header.command
        return when {
            group == SmpGroup.OS && command == OsCommand.INFO -> {
                osInfoGate?.await()
                if (refuseOsInfo) error(frame, SmpGroup.OS, 1) else ok(frame, OsInfoResponseWire.serializer(), OsInfoResponseWire(target))
            }
            group == SmpGroup.OS && command == OsCommand.MCUMGR_PARAMS ->
                if (refuseParams) {
                    listOf(responseTo(frame, SmpCbor.encode(SmpStatusWire.serializer(), SmpStatusWire(rc = ENOTSUP))))
                } else {
                    ok(frame, McuMgrParamsWire.serializer(), McuMgrParamsWire(bufferSize = BUFFER_SIZE, bufferCount = 4))
                }
            group == SmpGroup.OS && command == OsCommand.RESET -> {
                resetCount++
                ok(frame, EmptyWire.serializer(), EmptyWire())
            }
            group == SmpGroup.IMAGE && command == ImageCommand.STATE -> {
                if (frame.header.operation == SmpOperation.Write) {
                    writeState(SmpCbor.decode(ImageStateWriteWire.serializer(), frame.body))
                }
                ok(frame, ImageStateWire.serializer(), ImageStateWire(slots()))
            }
            group == SmpGroup.IMAGE && command == ImageCommand.UPLOAD -> {
                uploadGate?.await()
                imageBoard.answer(frame).also {
                    if (imageBoard.written.isNotEmpty()) uploadedHash = NEW_HASH
                }
            }
            else -> error(frame, group, 8)
        }
    }

    private fun writeState(write: ImageStateWriteWire) {
        if (write.confirm) {
            if (write.hash == null) activeConfirmed = true
        } else if (!ignoreTest && write.hash?.contentEquals(uploadedHash) == true) {
            pending = true
        }
    }

    private fun slots(): List<ImageSlotWire> = buildList {
        add(ImageSlotWire(slot = 0, version = "26.29.0", hash = activeHash, active = true, confirmed = activeConfirmed, bootable = true))
        uploadedHash?.let { hash ->
            add(ImageSlotWire(slot = 1, version = "26.43.1", hash = hash, pending = pending, bootable = true))
        }
    }

    private fun <T> ok(frame: SmpFrame, serializer: SerializationStrategy<T>, value: T): List<ByteArray> =
        listOf(responseTo(frame, SmpCbor.encode(serializer, value)))

    private fun error(frame: SmpFrame, group: Int, rc: Int): List<ByteArray> =
        listOf(responseTo(frame, SmpCbor.encode(SmpStatusWire.serializer(), SmpStatusWire(err = SmpErrWire(group, rc)))))

    companion object {
        const val BUFFER_SIZE = 2475
        private const val ENOTSUP = 8

        /** The MCUboot hash of the image that runs before the update. */
        val OLD_HASH = ByteArray(32) { 0x11 }

        /** The MCUboot hash that the board gives the uploaded image. */
        val NEW_HASH = ByteArray(32) { 0x22 }
    }
}
