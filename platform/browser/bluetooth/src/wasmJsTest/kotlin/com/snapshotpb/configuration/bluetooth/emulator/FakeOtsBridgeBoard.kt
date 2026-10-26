package com.snapshotpb.configuration.bluetooth.emulator

import com.snapshotpb.configuration.bluetooth.ots.OacpOpcode
import com.snapshotpb.configuration.bluetooth.ots.OacpResult
import com.snapshotpb.configuration.bluetooth.ots.ObjectPropertyBit
import com.snapshotpb.configuration.bluetooth.ots.OlcpOpcode
import com.snapshotpb.configuration.bluetooth.ots.OlcpResult
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.bluetooth.smp.SmpUuids

/**
 * An OTS server at the ATT level, on a [FakeBoardBridge]. It answers OLCP First/Next/Go To, the
 * object metadata reads, OACP READ (content chunks as notifications on ObjectData) and OACP WRITE
 * (content chunks written to ObjectData) over one OLCP cursor, as the board firmware does. The GATT
 * table also has a Device Information service, which the scan filters of the app do not name.
 *
 * With [smpService], the table also has the SMP service of a board with the update firmware. The
 * board does not answer SMP requests: the web engines have no write without response with a result,
 * so the app sends none.
 */
internal class FakeOtsBridgeBoard(smpService: Boolean = false) {
    class BoardObject(val name: String, var content: ByteArray, val writable: Boolean)

    val objects: List<BoardObject> = listOf(
        BoardObject("device", ByteArray(0), writable = false),
        BoardObject("device_config", ByteArray(0), writable = true),
        BoardObject("profiles", ByteArray(0), writable = true),
    )

    val bridge = FakeBoardBridge(
        services = listOf(
            BoardService(
                uuid = OtsUuids.Service.toString(),
                characteristics = listOf(
                    BoardCharacteristic(OtsUuids.Oacp.toString(), OACP, CONTROL_POINT, OACP + 1),
                    BoardCharacteristic(OtsUuids.Olcp.toString(), OLCP, CONTROL_POINT, OLCP + 1),
                    BoardCharacteristic(OtsUuids.ObjectName.toString(), NAME, GattProperty.READ, null),
                    BoardCharacteristic(OtsUuids.ObjectType.toString(), TYPE, GattProperty.READ, null),
                    BoardCharacteristic(OtsUuids.ObjectSize.toString(), SIZE, GattProperty.READ, null),
                    BoardCharacteristic(OtsUuids.ObjectId.toString(), ID, GattProperty.READ, null),
                    BoardCharacteristic(OtsUuids.ObjectProperties.toString(), PROPERTIES, GattProperty.READ, null),
                    BoardCharacteristic(
                        OtsUuids.ObjectData.toString(),
                        OBJECT_DATA,
                        OBJECT_DATA_PROPERTIES,
                        OBJECT_DATA + 1,
                    ),
                ),
            ),
            BoardService(
                uuid = DEVICE_INFORMATION_SERVICE,
                characteristics = listOf(
                    BoardCharacteristic(MANUFACTURER_NAME, MANUFACTURER, GattProperty.READ, null),
                ),
            ),
        ) + listOfNotNull(
            BoardService(
                uuid = SmpUuids.Service.toString(),
                characteristics = listOf(
                    BoardCharacteristic(SmpUuids.Characteristic.toString(), SMP, SMP_PROPERTIES, SMP + 1),
                ),
            ).takeIf { smpService },
        ),
    )

    /** Names of the objects whose OACP WRITE completed, in order. */
    val writtenObjects: MutableList<String> = mutableListOf()

    private var selected: Int? = null
    private var pendingWrite: Int? = null
    private var expectedLength = 0
    private var buffer = ByteArray(0)

    init {
        bridge.onWrite = ::onWrite
        bridge.values[MANUFACTURER] = "SnapshotPB".encodeToByteArray()
        select(null)
    }

    fun setContent(name: String, bytes: ByteArray) {
        objects.first { it.name == name }.content = bytes
    }

    fun content(name: String): ByteArray = objects.first { it.name == name }.content

    private fun onWrite(handle: Int, value: ByteArray) {
        when (handle) {
            OLCP -> handleOlcp(value)
            OACP -> handleOacp(value)
            OBJECT_DATA -> handleData(value)
        }
    }

    private fun handleOlcp(value: ByteArray) {
        val opcode = value[0]
        val result = when (opcode) {
            OlcpOpcode.FIRST -> {
                select(0)
                OlcpResult.Success
            }
            OlcpOpcode.NEXT -> {
                val next = (selected ?: -1) + 1
                if (next < objects.size) {
                    select(next)
                    OlcpResult.Success
                } else {
                    OlcpResult.OutOfBounds
                }
            }
            OlcpOpcode.GOTO -> {
                val index = (uInt48At(value, 1) - FIRST_OBJECT_ID).toInt()
                if (index in objects.indices) {
                    select(index)
                    OlcpResult.Success
                } else {
                    OlcpResult.ObjectIdNotFound
                }
            }
            else -> OlcpResult.OpCodeNotSupported
        }
        bridge.notify(OLCP, byteArrayOf(OlcpOpcode.RESPONSE_CODE, opcode, result.code))
    }

    private fun handleOacp(value: ByteArray) {
        val opcode = value[0]
        val index = selected
        val target = index?.let { objects[it] }
        when {
            opcode == OacpOpcode.READ && target != null -> {
                respondOacp(opcode, OacpResult.Success)
                target.content.toList().chunked(CHUNK_SIZE).forEach { chunk ->
                    bridge.notify(OBJECT_DATA, chunk.toByteArray())
                }
            }
            opcode == OacpOpcode.WRITE && target != null && target.writable -> {
                pendingWrite = index
                expectedLength = uInt32At(value, 5)
                buffer = ByteArray(0)
                respondOacp(opcode, OacpResult.Success)
                if (expectedLength == 0) commitWrite()
            }
            opcode == OacpOpcode.READ -> respondOacp(opcode, OacpResult.OperationFailed)
            opcode == OacpOpcode.WRITE -> respondOacp(opcode, OacpResult.ProcedureNotPermitted)
            else -> respondOacp(opcode, OacpResult.OpCodeNotSupported)
        }
    }

    private fun handleData(value: ByteArray) {
        if (pendingWrite == null) return
        buffer += value
        if (buffer.size >= expectedLength) commitWrite()
    }

    private fun commitWrite() {
        val index = pendingWrite ?: return
        objects[index].content = buffer
        writtenObjects += objects[index].name
        pendingWrite = null
        select(selected)
    }

    private fun respondOacp(opcode: Byte, result: OacpResult) {
        bridge.notify(OACP, byteArrayOf(OacpOpcode.RESPONSE_CODE, opcode, result.code))
    }

    /** Moves the OLCP cursor to [index] and puts the metadata of that object in the read values. */
    private fun select(index: Int?) {
        selected = index
        val target = index?.let { objects[it] }
        val size = target?.content?.size ?: 0
        val properties = when {
            target == null -> 0
            target.writable -> ObjectPropertyBit.READ or ObjectPropertyBit.WRITE
            else -> ObjectPropertyBit.READ
        }
        bridge.values[NAME] = (target?.name ?: "").encodeToByteArray()
        bridge.values[TYPE] = ByteArray(16)
        bridge.values[SIZE] = uInt32Bytes(size) + uInt32Bytes(size)
        // The firmware's IDs start at 0x100, so an ID uses more than one byte.
        bridge.values[ID] = ByteArray(6) { i -> ((FIRST_OBJECT_ID + (index ?: 0)) ushr (i * 8)).toByte() }
        bridge.values[PROPERTIES] = uInt32Bytes(properties)
    }

    private fun uInt48At(bytes: ByteArray, offset: Int): Long =
        (0 until 6).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8)) }

    private fun uInt32At(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((bytes[offset + i].toInt() and 0xFF) shl (i * 8)) }

    private fun uInt32Bytes(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (i * 8)).toByte() }

    companion object {
        const val OACP = 0x03
        const val OLCP = 0x06
        const val NAME = 0x09
        const val TYPE = 0x0B
        const val SIZE = 0x0D
        const val ID = 0x0F
        const val PROPERTIES = 0x11
        const val OBJECT_DATA = 0x13
        const val MANUFACTURER = 0x17
        const val SMP = 0x1A

        const val DEVICE_INFORMATION_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
        const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"

        private const val CONTROL_POINT = GattProperty.WRITE or GattProperty.INDICATE
        private const val OBJECT_DATA_PROPERTIES = GattProperty.WRITE_WITHOUT_RESPONSE or GattProperty.NOTIFY
        private const val SMP_PROPERTIES = GattProperty.WRITE_WITHOUT_RESPONSE or GattProperty.NOTIFY
        private const val CHUNK_SIZE = 20
        private const val FIRST_OBJECT_ID = 0x100L
    }
}
