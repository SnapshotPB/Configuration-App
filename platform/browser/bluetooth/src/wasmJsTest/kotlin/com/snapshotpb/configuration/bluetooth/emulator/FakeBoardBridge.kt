package com.snapshotpb.configuration.bluetooth.emulator

/**
 * A [BoardBridge] for tests. It holds a GATT table and a value for each handle, records each call,
 * and lets a test set the status of each operation and send events. A read gives the part of the
 * value from the offset, at most [readPayload] bytes, as an ATT server with an MTU of 65 does.
 */
internal class FakeBoardBridge(
    var advertisement: BoardAdvertisement? = BoardAdvertisement(BOARD_ID, BOARD_NAME),
    var services: List<BoardService> = emptyList(),
    var connected: Boolean = false,
) : BoardBridge {
    class Write(val handle: Int, val value: ByteArray, val withResponse: Boolean)

    val values: MutableMap<Int, ByteArray> = mutableMapOf()
    var readPayload: Int = 64

    var connectStatus: Int = 0
    var readStatus: Int = 0
    var writeStatus: Int = 0
    var subscribeStatus: Int = 0

    /** When true, [disconnect] sends a 'disconnected' event, as the bridge of the viewer can. */
    var eventOnDisconnect: Boolean = true

    /** Runs after each write that succeeds. An OTS board answers its control points here. */
    var onWrite: ((handle: Int, value: ByteArray) -> Unit)? = null

    val connectCalls: MutableList<Int> = mutableListOf()
    var disconnectCalls: Int = 0
    val reads: MutableList<Pair<Int, Int>> = mutableListOf()
    val writes: MutableList<Write> = mutableListOf()
    val subscriptions: MutableList<Pair<Int, Int>> = mutableListOf()

    private val listeners = mutableListOf<(BoardEvent) -> Unit>()

    val listenerCount: Int get() = listeners.size

    override fun scan(): BoardAdvertisement? = advertisement

    override suspend fun connect(mtu: Int): Int {
        connectCalls += mtu
        if (connectStatus == 0) connected = true
        return connectStatus
    }

    override suspend fun disconnect() {
        disconnectCalls++
        val wasConnected = connected
        connected = false
        if (wasConnected && eventOnDisconnect) emit(BoardEvent.Disconnected(LOCAL_HOST_TERMINATED))
    }

    override fun isConnected(): Boolean = connected

    override fun gatt(): List<BoardService> = services

    override suspend fun read(handle: Int, offset: Int): BoardReadResult {
        reads += handle to offset
        if (readStatus != 0) return BoardReadResult(readStatus, ByteArray(0))
        val value = values[handle] ?: ByteArray(0)
        if (offset > value.size) return BoardReadResult(ATT_INVALID_OFFSET, ByteArray(0))
        return BoardReadResult(0, value.copyOfRange(offset, minOf(value.size, offset + readPayload)))
    }

    override suspend fun write(handle: Int, value: ByteArray, withResponse: Boolean): Int {
        writes += Write(handle, value.copyOf(), withResponse)
        if (writeStatus != 0) return writeStatus
        onWrite?.invoke(handle, value)
        return 0
    }

    override suspend fun subscribe(handle: Int, mode: Int): Int {
        subscriptions += handle to mode
        return subscribeStatus
    }

    override fun addEventListener(listener: (BoardEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun emit(event: BoardEvent) {
        listeners.toList().forEach { it(event) }
    }

    fun notify(handle: Int, value: ByteArray) {
        emit(BoardEvent.Value(handle, value))
    }

    companion object {
        const val BOARD_ID = "5350425f4f5440008000000000000001"
        const val BOARD_NAME = "Snapshot PB"
        const val ATT_INVALID_OFFSET = 0x07
        const val LOCAL_HOST_TERMINATED = 0x16
    }
}
