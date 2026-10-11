package com.snapshotpb.configuration.bluetooth.emulator

/**
 * The Kotlin side of `window.snapshotBoard`, the bridge that the 3D viewer of the board puts on its
 * window. The viewer runs a WebAssembly model of the board firmware, and the bridge gives access to
 * the GATT server of that model. [JsBoardBridge] adapts the JavaScript object; tests use a fake.
 *
 * The bridge has one board. A handle is the ATT handle of a characteristic value. A status is 0, or
 * an ATT error code.
 */
internal interface BoardBridge {
    /** The advertisement of the board, or null while the model does not advertise. */
    fun scan(): BoardAdvertisement?

    /** Connects and asks for an ATT MTU of [mtu]. Returns 0, or a negative errno. */
    suspend fun connect(mtu: Int): Int

    suspend fun disconnect()

    fun isConnected(): Boolean

    /** The GATT table of the board. It is valid while the board is connected. */
    fun gatt(): List<BoardService>

    suspend fun read(handle: Int, offset: Int): BoardReadResult

    /** Returns 0, or an ATT error code. */
    suspend fun write(handle: Int, value: ByteArray, withResponse: Boolean): Int

    /** Writes the CCC of [handle]: 0 off, 1 notify, 2 indicate. Returns 0, or an ATT error code. */
    suspend fun subscribe(handle: Int, mode: Int): Int

    /** Adds [listener] for the events of the board. Returns a function that removes it. */
    fun addEventListener(listener: (BoardEvent) -> Unit): () -> Unit
}

/** The part of an advertisement that the engine uses. Web Bluetooth gives a page no more. */
internal class BoardAdvertisement(val id: String, val name: String?)

internal class BoardService(val uuid: String, val characteristics: List<BoardCharacteristic>)

/**
 * A characteristic in the GATT table. [handle] is the value handle, [properties] holds the GATT
 * property bits, and [ccc] is the handle of the CCC descriptor, or null when there is none.
 */
internal class BoardCharacteristic(val uuid: String, val handle: Int, val properties: Int, val ccc: Int?)

internal class BoardReadResult(val status: Int, val value: ByteArray)

internal sealed interface BoardEvent {
    /** A notification or an indication. Web Bluetooth gives a page the same event for the two. */
    class Value(val handle: Int, val value: ByteArray) : BoardEvent

    class Disconnected(val reason: Int) : BoardEvent

    class Advertising(val on: Boolean) : BoardEvent
}

/** The GATT characteristic property bits that the engine reads. */
internal object GattProperty {
    const val READ = 0x02
    const val WRITE_WITHOUT_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
}
