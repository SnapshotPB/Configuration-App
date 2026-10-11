package com.snapshotpb.configuration.bluetooth.ots


import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.CharacteristicReadResult
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT-only Object Transfer Service client.
 *
 * Resolves the OTS service on a connected [peripheral], subscribes to the OACP
 * and OLCP indications, and exposes suspend operations that wrap the
 * control-point request/response dance defined in the OTS spec (§3.11, §3.12).
 *
 * **Non-standard content transport.** The OTS spec streams object *content*
 * over an L2CAP connection-oriented channel; this client does not. L2CAP CoC
 * is unavailable on the Windows desktop target, so [readSelectedObjectBytes]
 * instead reassembles the object from chunks the firmware notifies on a vendor
 * [OtsUuids.ObjectData] characteristic after the OACP `READ` handshake. The
 * control plane (OACP/OLCP, metadata reads) stays fully spec-compliant; only
 * the data plane is a private firmware contract.
 *
 * Lifecycle: a single instance is bound to one connected peripheral. Construct
 * via [discover] after services are populated; call [close] when the device
 * disconnects so indication/notification subscriptions can be torn down.
 */
class OtsClient private constructor(
    private val blueFalcon: BlueFalcon,
    private val peripheral: BluetoothPeripheral,
    private val service: BluetoothService,
    private val awaitSubscribed: suspend (BluetoothCharacteristic) -> Unit,
) {
    private val oacp: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.Oacp)
    private val olcp: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.Olcp)
    private val objectName: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.ObjectName)
    private val objectType: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.ObjectType)
    private val objectSize: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.ObjectSize)
    private val objectId: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.ObjectId)
    private val objectProperties: BluetoothCharacteristic = service.requireCharacteristic(OtsUuids.ObjectProperties)

    /**
     * Vendor "Object Data" notify characteristic carrying object content
     * chunks. Optional: not every OTS peripheral implements this vendor
     * extension, so [readSelectedObjectBytes] fails loudly if it is absent.
     */
    private val objectData: BluetoothCharacteristic? = service.findCharacteristic(OtsUuids.ObjectData)

    /**
     * True after [prepare] subscribes to [objectData] notifications. The subscription
     * stays for the life of the client, and [close] removes it.
     */
    private var objectDataSubscribed = false

    /**
     * Android (and most BLE stacks) only allow one GATT operation in flight per
     * connection; this mutex serializes every read/write the client issues so
     * back-to-back metadata reads don't race each other.
     */
    private val gattMutex = Mutex()

    /**
     * Serializes each OTS transaction. The board has one OLCP cursor for each link, and a transfer
     * operates on the object that the cursor selects. [gattMutex] covers one GATT operation only, so
     * without this lock a second "select, then transfer" sequence can move the cursor between the
     * select and the transfer of the first, and an OACP WRITE then goes to the wrong object.
     */
    private val transactionMutex = Mutex()

    /**
     * Object IDs by object name, recorded by [indexObjects] and [listObjects]. With an ID,
     * [selectObjectByName] selects the object with one OLCP Go To, not a First/Next walk that reads
     * the name of each object on the way. Only read and written inside a [transaction].
     */
    private val objectIds = mutableMapOf<String, Long>()

    /**
     * Runs [block] as one OTS transaction: no other transaction on this client starts until it ends.
     * Put every sequence that selects an object and then reads or writes it inside one call.
     *
     * The lock is not reentrant. Do not call [transaction] or [listObjects] inside [block].
     */
    suspend fun <T> transaction(block: suspend OtsClient.() -> T): T =
        transactionMutex.withLock { block() }

    /**
     * Enables indications on OACP & OLCP and, when present, notifications on the vendor
     * [OtsUuids.ObjectData] characteristic. Must be called once after construction.
     *
     * After each subscription, [awaitSubscribed] waits until the platform stack has written the CCC.
     * The wait is per platform (see `PlatformBluetoothSeams.awaitSubscribed`): an OLCP write that
     * goes out before the OLCP CCC write completes gets no indication and times out.
     *
     * ObjectData is subscribed here, once, rather than toggled around each object read: on iOS,
     * enabling a notification immediately before each OACP READ intermittently wedged CoreBluetooth's
     * operation queue so the OACP write never went out (surfaced as `OtsTimeout` on the 2nd+ object).
     * Leaving it subscribed for the client's lifetime removes that per-read toggle; [close] tears it
     * down. The firmware only notifies ObjectData in response to an OACP READ, so nothing streams
     * between reads.
     */
    suspend fun prepare() {
        gattMutex.withLock {
            blueFalcon.indicateCharacteristic(peripheral, oacp, indicate = true)
            awaitSubscribed(oacp)
            blueFalcon.indicateCharacteristic(peripheral, olcp, indicate = true)
            awaitSubscribed(olcp)
            objectData?.let { dataChar ->
                blueFalcon.notifyCharacteristic(peripheral, dataChar, notify = true)
                objectDataSubscribed = true
                awaitSubscribed(dataChar)
            }
        }
    }

    /**
     * Releases the OACP and OLCP indication subscriptions, plus the vendor
     * [OtsUuids.ObjectData] notification subscription if [prepare] made one.
     */
    suspend fun close() {
        gattMutex.withLock {
            runCatching { blueFalcon.indicateCharacteristic(peripheral, oacp, indicate = false) }
            runCatching { blueFalcon.indicateCharacteristic(peripheral, olcp, indicate = false) }
            if (objectDataSubscribed) {
                objectData?.let { runCatching { blueFalcon.notifyCharacteristic(peripheral, it, notify = false) } }
                objectDataSubscribed = false
            }
        }
    }

    /**
     * Walks every object on the peripheral via OLCP First + Next, reading
     * metadata for each. Stops on the first `NoObject` / `OutOfBounds` from
     * the server. The walk moves the OLCP cursor, so it runs as one [transaction].
     */
    suspend fun listObjects(): List<OtsObject> = transaction {
        val collected = mutableListOf<OtsObject>()
        if (tryOlcp(OlcpOpcode.FIRST)) {
            while (true) {
                collected += readSelectedObject().also { objectIds[it.name] = it.id }
                if (!tryOlcp(OlcpOpcode.NEXT)) break
            }
        }
        collected
    }

    /**
     * Walks every object via OLCP First + Next and records the ID of each by name, so that
     * [selectObjectByName] can select it with one OLCP Go To. This reads two metadata values for
     * each object, not the five that [listObjects] reads. Returns the IDs by name.
     */
    suspend fun indexObjects(): Map<String, Long> = transaction {
        if (tryOlcp(OlcpOpcode.FIRST)) {
            while (true) {
                val name = readValue(objectName)?.decodeToString()
                val idBytes = readValue(objectId)
                if (name != null && idBytes != null && idBytes.size >= 6) {
                    objectIds[name] = idBytes.readUInt48LE(0)
                }
                if (!tryOlcp(OlcpOpcode.NEXT)) break
            }
        }
        objectIds.toMap()
    }

    /** Reads metadata for the object currently selected via OLCP. */
    suspend fun readSelectedObject(): OtsObject {
        val name = readValue(objectName)?.decodeToString() ?: ""
        val type = readValue(objectType) ?: ByteArray(0)
        val sizeBytes = readValue(objectSize) ?: ByteArray(8)
        val idBytes = readValue(objectId) ?: ByteArray(6)
        val propsBytes = readValue(objectProperties) ?: ByteArray(4)
        val current = if (sizeBytes.size >= 4) sizeBytes.readUInt32LE(0) else 0L
        val allocated = if (sizeBytes.size >= 8) sizeBytes.readUInt32LE(4) else current
        val id = if (idBytes.size >= 6) idBytes.readUInt48LE(0) else 0L
        val props = if (propsBytes.size >= 4) propsBytes.readUInt32LE(0).toInt() else 0
        return OtsObject(
            id = id,
            name = name,
            sizeBytes = current,
            allocatedBytes = allocated,
            properties = props,
            typeUuid = type,
        )
    }

    /**
     * Issues OACP Read for the currently selected object, asking the server to
     * make `[offset, offset+length)` bytes available.
     *
     * **Does not consume the data** — only the OACP handshake is performed.
     * Returns the OACP response code; on [OacpResult.Success] the firmware
     * begins notifying content chunks on the vendor [OtsUuids.ObjectData]
     * characteristic (this client's non-standard substitute for the spec's
     * L2CAP channel). [readSelectedObjectBytes] drives the full handshake +
     * chunk reassembly.
     */
    suspend fun requestObjectRead(offset: Long, length: Long): OacpResult {
        val payload = ByteArray(8).also { buf ->
            offset.toUInt32LE().copyInto(buf, destinationOffset = 0)
            length.toUInt32LE().copyInto(buf, destinationOffset = 4)
        }
        return sendOacp(OacpOpcode.READ, payload)
    }

    /**
     * Selects the object whose Object Name is [name].
     *
     * When [indexObjects] or [listObjects] recorded the object's ID, one OLCP Go To selects it. If
     * the board refuses the Go To (a server with no Go To support, or an ID it no longer knows), the
     * ID is dropped and the selection falls back to the walk: OLCP First/Next, reading each object's
     * Object Name, until one matches. A Go To with no response keeps the ID: the board drops OLCP
     * while it commits a write, and the ID is still good after the commit.
     *
     * On a match the matching object is left **selected** so a follow-up
     * OTS read or write operation operates on it. Returns `false` if nothing matched. Call it and
     * the transfer that follows inside one [transaction].
     */
    suspend fun selectObjectByName(name: String): Boolean {
        objectIds[name]?.let { id ->
            when (sendOlcp(OlcpOpcode.GOTO, id.toUInt48LE())) {
                OlcpResult.Success -> return true
                null -> return false
                else -> objectIds.remove(name)
            }
        }
        if (!tryOlcp(OlcpOpcode.FIRST)) return false
        while (true) {
            val objName = readValue(objectName)?.decodeToString() ?: ""
            if (objName == name) return true
            if (!tryOlcp(OlcpOpcode.NEXT)) return false
        }
    }

    /**
     * Writes the full content of the currently selected object.
     *
     * Non-standard transport (see the class doc): rather than an L2CAP channel,
     * the client writes content chunks directly to the vendor [OtsUuids.ObjectData]
     * characteristic after the OACP `WRITE` handshake.
     *
     * @throws OtsCharacteristicMissing if the peripheral lacks the vendor
     *   [OtsUuids.ObjectData] characteristic.
     * @throws OacpFailure if the selected object does not permit OACP write.
     */
    suspend fun writeSelectedObjectBytes(data: ByteArray) {
        val dataChar = objectData
            ?: throw OtsCharacteristicMissing(OtsUuids.ObjectData.toString())

        // 1. Writeability check.
        val propsBytes = readValue(objectProperties) ?: ByteArray(0)
        val props = if (propsBytes.size >= 4) propsBytes.readUInt32LE(0).toInt() else 0
        if (props and ObjectPropertyBit.WRITE == 0) {
            throw OacpFailure(OacpOpcode.WRITE, OacpResult.ProcedureNotPermitted, OacpResult.ProcedureNotPermitted.code)
        }

        // 2. OACP WRITE handshake (offset 0, length = data.size, mode = Write/Patch (0x00)).
        val payload = ByteArray(9).also { buf ->
            0L.toUInt32LE().copyInto(buf, destinationOffset = 0)
            data.size.toLong().toUInt32LE().copyInto(buf, destinationOffset = 4)
            buf[8] = 0x00.toByte() // Mode = 0 (Write/Patch)
        }
        sendOacp(OacpOpcode.WRITE, payload)

        // 3. Write chunks directly to the vendor ObjectData characteristic.
        val chunkSize = 20
        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            gattMutex.withLock {
                blueFalcon.writeCharacteristic(peripheral, dataChar, chunk)
            }
            offset = end
            if (offset < data.size) {
                delay(250)
            }
        }
    }

    /**
     * Reads the full content of the currently selected object.
     *
     * Non-standard transport (see the class doc): rather than an L2CAP channel,
     * the firmware notifies content chunks on the vendor [OtsUuids.ObjectData]
     * characteristic after the OACP `READ` handshake. The steps:
     *
     * 1. Read Object Size metadata for the expected byte count and verify the
     *    object advertises the OACP-read property.
     * 2. Launch an [CoroutineStart.UNDISPATCHED] collector on the
     *    [OtsUuids.ObjectData] notifications that accumulates chunks. The
     *    subscription itself comes from [prepare] and stays for the life of the
     *    client. The collector uses the same race-free pattern as
     *    [awaitControlResponse], so no chunk is lost before the OACP `READ`.
     * 3. Issue OACP `READ` (offset 0, length = size) and await its success
     *    indication.
     * 4. Await `accumulated == size` (with a timeout), stop the collector,
     *    return. The subscription stays until [close].
     *
     * @throws OtsCharacteristicMissing if the peripheral lacks the vendor
     *   [OtsUuids.ObjectData] characteristic.
     * @throws OacpFailure if the selected object does not permit OACP read.
     * @throws OtsTransferIncomplete on truncation or timeout.
     */
    suspend fun readSelectedObjectBytes(): ByteArray {
        val data = objectData
            ?: throw OtsCharacteristicMissing(OtsUuids.ObjectData.toString())

        // 1. Expected size + readability check.
        val sizeBytes = readValue(objectSize) ?: ByteArray(0)
        val expectedSize = if (sizeBytes.size >= 4) sizeBytes.readUInt32LE(0) else 0L
        val propsBytes = readValue(objectProperties) ?: ByteArray(0)
        val props = if (propsBytes.size >= 4) propsBytes.readUInt32LE(0).toInt() else 0
        if (props and ObjectPropertyBit.READ == 0) {
            throw OacpFailure(OacpOpcode.READ, OacpResult.ProcedureNotPermitted, OacpResult.ProcedureNotPermitted.code)
        }

        val accumulator = ChunkAccumulator(expectedSize)
        // Empty object: nothing will be notified, so start no collector and send no READ.
        if (expectedSize == 0L) return accumulator.result()

        return coroutineScope {
            // 2. Start an undispatched collector so it is already suspended on `notifications` before
            // the OACP READ fires. ObjectData notifications are enabled once in [prepare] and kept on
            // for the client's lifetime, so there is no per-read notify toggle (see the note there).
            val complete = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                data.notifications.collect { chunk ->
                    accumulator.append(chunk)
                    if (accumulator.isDone) {
                        complete.complete(Unit)
                        return@collect
                    }
                }
            }
            try {
                // 3. OACP READ handshake (offset 0, full length).
                requestObjectRead(offset = 0, length = expectedSize)

                // 4. Await completion within the timeout.
                val finished = withTimeoutOrNull(CONTENT_TRANSFER_TIMEOUT_MILLIS) {
                    complete.await()
                    true
                } ?: false
                if (!finished || !accumulator.isComplete) {
                    throw OtsTransferIncomplete(expectedSize, accumulator.received)
                }
                accumulator.result()
            } finally {
                // ObjectData stays subscribed for the client's lifetime (torn down in [close]); only
                // the per-read content collector is cancelled here.
                collector.cancel()
            }
        }
    }

    private suspend fun tryOlcp(opcode: Byte): Boolean =
        sendOlcp(opcode, ByteArray(0)) == OlcpResult.Success

    private suspend fun sendOlcp(opcode: Byte, params: ByteArray): OlcpResult? {
        val command = byteArrayOf(opcode) + params
        val response = awaitControlResponse(olcp) {
            blueFalcon.writeCharacteristic(peripheral, olcp, command)
        }
        if (response == null || response.size < 3) return null
        if (response[0] != OlcpOpcode.RESPONSE_CODE || response[1] != opcode) return null
        return OlcpResult.fromCode(response[2])
    }

    private suspend fun sendOacp(opcode: Byte, params: ByteArray): OacpResult {
        val command = byteArrayOf(opcode) + params
        val response = awaitControlResponse(oacp) {
            blueFalcon.writeCharacteristic(peripheral, oacp, command)
        } ?: throw OtsTimeout("OACP opcode 0x${opcode.toUByte().toString(16)} timed out")
        if (response.size < 3 || response[0] != OacpOpcode.RESPONSE_CODE || response[1] != opcode) {
            throw OacpFailure(opcode, null, response.getOrElse(2) { 0 })
        }
        val result = OacpResult.fromCode(response[2])
        if (result != OacpResult.Success) {
            throw OacpFailure(opcode, result, response[2])
        }
        return result
    }

    /**
     * Subscribes to the control-point's indication flow *before* issuing the
     * write, then waits for the first emission that follows. BlueFalcon surfaces
     * indications on a non-replaying [kotlinx.coroutines.flow.SharedFlow], so
     * subscribing after the write would race the response.
     *
     * The collector is launched with [CoroutineStart.UNDISPATCHED] so it runs
     * eagerly through the SharedFlow subscribe-and-suspend on the calling
     * thread before [send] gets a chance to fire.
     */
    private suspend fun awaitControlResponse(
        controlPoint: BluetoothCharacteristic,
        send: suspend () -> Unit,
    ): ByteArray? = withTimeoutOrNull(CONTROL_RESPONSE_TIMEOUT_MILLIS) {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                controlPoint.notifications.first()
            }
            gattMutex.withLock { send() }
            response.await()
        }
    }

    /**
     * Reads [characteristic] and returns the value that the stack delivered, or null when the read
     * failed or timed out.
     *
     * Since BlueFalcon 3.7 (ADR 0014), `readCharacteristic` suspends until the platform delivers
     * the value and returns it as a [CharacteristicReadResult]. The older API returned before the
     * value arrived, so this client used to poll `value` for a new array reference (Android,
     * desktop) or wait for the read on `notifications` (iOS). The returned result replaces both.
     *
     * Wrapped in [gattMutex] because a stack allows one GATT op in flight; a
     * concurrent read or control-point write would clobber our pending response.
     */
    private suspend fun readValue(characteristic: BluetoothCharacteristic): ByteArray? =
        gattMutex.withLock {
            // The read is idempotent, so retry a failed one a few times: a null here breaks the
            // OLCP First/Next selection walk (which reads each object's name), surfacing as a
            // spurious OtsObjectNotFound. A disconnected link or an unsupported read cannot
            // recover, so those stop at once.
            repeat(READ_ATTEMPTS) { attempt ->
                when (val result = readOnce(characteristic)) {
                    is CharacteristicReadResult.Success -> result.value?.let { return@withLock it }
                    is CharacteristicReadResult.Disconnected,
                    is CharacteristicReadResult.Unsupported -> return@withLock null
                    is CharacteristicReadResult.Failed, null -> Unit
                }
                if (attempt < READ_ATTEMPTS - 1) delay(READ_RETRY_DELAY_MILLIS)
            }
            null
        }

    /** A single read attempt: the engine's result, or null when [READ_TIMEOUT_MILLIS] passed first. */
    private suspend fun readOnce(characteristic: BluetoothCharacteristic): CharacteristicReadResult? =
        withTimeoutOrNull(READ_TIMEOUT_MILLIS) { blueFalcon.readCharacteristic(peripheral, characteristic) }

    companion object {
        private const val CONTROL_RESPONSE_TIMEOUT_MILLIS = 5_000L
        /**
         * A safety net over the engine's own read completion, so it is longer than the engines'
         * own read timeouts (10 s on Android, Apple, Linux, Windows and macOS). The engine then
         * ends a read that gets no response and frees it, so the retry in [readValue] can run. A
         * shorter timeout here cancels the call while the engine still holds the read, and the
         * engine refuses the retry at once.
         */
        private const val READ_TIMEOUT_MILLIS = 12_000L

        /** Retry a failed (null) metadata read. */
        private const val READ_ATTEMPTS = 3
        private const val READ_RETRY_DELAY_MILLIS = 200L

        /**
         * Upper bound on how long [readSelectedObjectBytes] waits for the
         * firmware to notify every content chunk after the OACP `READ`
         * handshake. The vendor `Device` object is tens of bytes, so a single
         * notification usually suffices; this is generous slack.
         */
        private const val CONTENT_TRANSFER_TIMEOUT_MILLIS = 10_000L

        /**
         * Resolves the OTS service on [peripheral] and returns a prepared
         * client, or `null` if the peripheral does not expose OTS.
         *
         * Caller must ensure services have already been discovered (BlueFalcon's
         * Android engine does this automatically when constructed with
         * `autoDiscoverAllServicesAndCharacteristics = true`).
         *
         * [awaitSubscribed] runs after each subscription in [prepare]; see there.
         */
        suspend fun discover(
            blueFalcon: BlueFalcon,
            peripheral: BluetoothPeripheral,
            awaitSubscribed: suspend (BluetoothCharacteristic) -> Unit,
        ): OtsClient? {
            val service = peripheral.services.firstOrNull { it.uuid == OtsUuids.Service }
                ?: return null
            val client = OtsClient(blueFalcon, peripheral, service, awaitSubscribed)
            try {
                client.prepare()
            } catch (error: Throwable) {
                // prepare() may have already enabled some indication subscriptions before failing;
                // release them so a partially-prepared client is never discarded still subscribed
                // (finding #6). Best-effort — rethrow the original failure (incl. cancellation).
                runCatching { client.close() }
                throw error
            }
            return client
        }
    }
}

private fun BluetoothService.requireCharacteristic(uuid: Uuid): BluetoothCharacteristic =
    findCharacteristic(uuid) ?: throw OtsCharacteristicMissing(uuid.toString())

private fun BluetoothService.findCharacteristic(uuid: Uuid): BluetoothCharacteristic? =
    characteristics.firstOrNull { it.uuid == uuid }
