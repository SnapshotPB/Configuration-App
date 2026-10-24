package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OacpOpcode
import com.snapshotpb.configuration.bluetooth.ots.OacpResult
import com.snapshotpb.configuration.bluetooth.ots.ObjectPropertyBit
import com.snapshotpb.configuration.bluetooth.ots.OlcpOpcode
import com.snapshotpb.configuration.bluetooth.ots.OlcpResult
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A characteristic whose value the fake board sets. A read stores the new value, and the fake engine
 * returns it from `readCharacteristic`, as the BlueFalcon 3.7 engines do (ADR 0014).
 */
internal class FakeOtsCharacteristic(override val uuid: Uuid) : BluetoothCharacteristic {
    var current: ByteArray? = null
    val emitter = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val name: String? = null
    override val value: ByteArray? get() = current
    override val notifications: SharedFlow<ByteArray> get() = emitter
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList()

    /** False, so the engine's debug observer leaves these characteristics alone. */
    override val isNotifying: Boolean = false
    override val service: BluetoothService? = null
}

/**
 * A GATT-level OTS server for engine tests. It answers OLCP First/Next/Go To, the object metadata reads,
 * OACP READ (content chunks on ObjectData) and OACP WRITE (content chunks written to ObjectData)
 * the way the board firmware does, over one shared OLCP cursor. Tests control it with gates that
 * park a read or a write at a known point.
 */
internal class FakeOtsBoard(
    peripheralUuid: String = "board-peripheral",
    peripheralName: String? = "Snapshot PB",
    /** More services of the board, for example the SMP service of [FakeSmpBoard]. */
    extraServices: List<BluetoothService> = emptyList(),
) {
    class BoardObject(val name: String, var content: ByteArray, val writable: Boolean)

    val objects: List<BoardObject> = listOf(
        BoardObject("device", ByteArray(0), writable = false),
        BoardObject("device_config", ByteArray(0), writable = true),
        BoardObject("profiles", ByteArray(0), writable = true),
    )

    private val oacp = FakeOtsCharacteristic(OtsUuids.Oacp)
    private val olcp = FakeOtsCharacteristic(OtsUuids.Olcp)
    private val objectName = FakeOtsCharacteristic(OtsUuids.ObjectName)
    private val objectType = FakeOtsCharacteristic(OtsUuids.ObjectType)
    private val objectSize = FakeOtsCharacteristic(OtsUuids.ObjectSize)
    private val objectId = FakeOtsCharacteristic(OtsUuids.ObjectId)
    private val objectProperties = FakeOtsCharacteristic(OtsUuids.ObjectProperties)
    private val objectData = FakeOtsCharacteristic(OtsUuids.ObjectData)

    private val service = FakeBluetoothService(
        uuid = OtsUuids.Service,
        characteristics = listOf(
            oacp, olcp, objectName, objectType, objectSize, objectId, objectProperties, objectData,
        ),
    )

    val peripheral = FakeBluetoothPeripheral(
        uuid = peripheralUuid,
        name = peripheralName,
        services = listOf(service) + extraServices,
        characteristics = service.characteristics + extraServices.flatMap { it.characteristics },
    )

    /** Index of the object that the OLCP cursor selects. */
    var selected: Int? = null
        private set

    /** Every OLCP opcode written, in order. */
    val olcpOpcodes: MutableList<Byte> = mutableListOf()

    /** When false, the board answers OLCP Go To with "op code not supported", as a minimal server does. */
    var supportsGoTo: Boolean = true

    /**
     * When set, the first OLCP Go To after the next committed write gets no response, as the
     * firmware drops OLCP while it commits a written object.
     */
    var dropGoToAfterCommit: Boolean = false
    private var dropNextGoTo = false

    /** Names of the objects whose OACP WRITE completed, in order. */
    val writtenObjects: MutableList<String> = mutableListOf()

    /** When set with [readGate], an OACP READ of this object waits for the gate before it answers. */
    var gatedReadObject: String? = null
    var readGate: CompletableDeferred<Unit>? = null

    /** When set, every content chunk written to ObjectData waits for this gate. */
    var writeChunkGate: CompletableDeferred<Unit>? = null

    /** An OACP READ of this object answers with a failure. */
    var failReadOf: String? = null

    /** An OACP WRITE of this object answers with a failure. */
    var failWriteOf: String? = null

    /** Lets a test change what the board stores for a write, as firmware that clamps a value does. */
    var onCommit: ((name: String, bytes: ByteArray) -> ByteArray)? = null

    private var pendingWrite: Int? = null
    private var expectedLength = 0
    private var buffer = ByteArray(0)

    fun setContent(name: String, bytes: ByteArray) {
        objects.first { it.name == name }.content = bytes
    }

    fun content(name: String): ByteArray = objects.first { it.name == name }.content

    fun install(engine: FakeBlueFalconEngine) {
        engine.onWriteCharacteristic = { characteristic, value -> onWrite(characteristic, value) }
        engine.onReadCharacteristic = { characteristic -> onRead(characteristic) }
    }

    private suspend fun onWrite(characteristic: BluetoothCharacteristic, value: ByteArray) {
        when (characteristic) {
            olcp -> handleOlcp(value)
            oacp -> handleOacp(value)
            objectData -> handleData(value)
        }
    }

    private fun handleOlcp(value: ByteArray) {
        val opcode = value[0]
        olcpOpcodes += opcode
        val result = when (opcode) {
            OlcpOpcode.GOTO -> {
                if (dropNextGoTo) {
                    dropNextGoTo = false
                    return
                }
                val index = if (value.size >= 7) (uInt48At(value, 1) - FIRST_OBJECT_ID).toInt() else -1
                when {
                    !supportsGoTo -> OlcpResult.OpCodeNotSupported
                    index in objects.indices -> {
                        selected = index
                        OlcpResult.Success
                    }
                    else -> OlcpResult.ObjectIdNotFound
                }
            }
            OlcpOpcode.FIRST -> {
                selected = 0
                OlcpResult.Success
            }
            OlcpOpcode.NEXT -> {
                val next = (selected ?: -1) + 1
                if (next < objects.size) {
                    selected = next
                    OlcpResult.Success
                } else {
                    OlcpResult.OutOfBounds
                }
            }
            else -> OlcpResult.OpCodeNotSupported
        }
        olcp.emitter.tryEmit(byteArrayOf(OlcpOpcode.RESPONSE_CODE, opcode, result.code))
    }

    private suspend fun handleOacp(value: ByteArray) {
        val opcode = value[0]
        val index = selected
        val target = index?.let { objects[it] }
        when (opcode) {
            OacpOpcode.READ -> {
                if (target != null && target.name == gatedReadObject) readGate?.await()
                if (target == null || target.name == failReadOf) {
                    respondOacp(opcode, OacpResult.OperationFailed)
                    return
                }
                respondOacp(opcode, OacpResult.Success)
                target.content.toList().chunked(CHUNK_SIZE).forEach { chunk ->
                    objectData.emitter.tryEmit(chunk.toByteArray())
                }
            }
            OacpOpcode.WRITE -> {
                if (target == null || !target.writable) {
                    respondOacp(opcode, OacpResult.ProcedureNotPermitted)
                    return
                }
                if (target.name == failWriteOf) {
                    respondOacp(opcode, OacpResult.OperationFailed)
                    return
                }
                pendingWrite = index
                expectedLength = value.uInt32At(5)
                buffer = ByteArray(0)
                respondOacp(opcode, OacpResult.Success)
                if (expectedLength == 0) commitWrite()
            }
            else -> respondOacp(opcode, OacpResult.OpCodeNotSupported)
        }
    }

    private suspend fun handleData(value: ByteArray) {
        writeChunkGate?.await()
        if (pendingWrite == null) return
        buffer += value
        if (buffer.size >= expectedLength) commitWrite()
    }

    private fun commitWrite() {
        val index = pendingWrite ?: return
        val target = objects[index]
        target.content = onCommit?.invoke(target.name, buffer) ?: buffer
        writtenObjects += target.name
        pendingWrite = null
        if (dropGoToAfterCommit) dropNextGoTo = true
    }

    private fun respondOacp(opcode: Byte, result: OacpResult) {
        oacp.emitter.tryEmit(byteArrayOf(OacpOpcode.RESPONSE_CODE, opcode, result.code))
    }

    private fun onRead(characteristic: BluetoothCharacteristic) {
        val target = selected?.let { objects[it] }
        val fresh: ByteArray = when (characteristic) {
            objectName -> (target?.name ?: "").encodeToByteArray()
            objectType -> ByteArray(16)
            objectSize -> {
                val size = target?.content?.size ?: 0
                uInt32Bytes(size) + uInt32Bytes(size)
            }
            // The firmware's IDs start at 0x100, so an ID uses more than one byte.
            objectId -> ByteArray(6) { i -> ((FIRST_OBJECT_ID + (selected ?: 0)) ushr (i * 8)).toByte() }
            objectProperties -> {
                val props = if (target?.writable == true) {
                    ObjectPropertyBit.READ or ObjectPropertyBit.WRITE
                } else {
                    ObjectPropertyBit.READ
                }
                uInt32Bytes(props)
            }
            else -> return
        }
        (characteristic as FakeOtsCharacteristic).current = fresh
    }

    private fun uInt48At(bytes: ByteArray, offset: Int): Long =
        (0 until 6).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8)) }

    private fun uInt32Bytes(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (i * 8)).toByte() }

    private fun ByteArray.uInt32At(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        private const val CHUNK_SIZE = 20
        private const val FIRST_OBJECT_ID = 0x100L
    }
}
