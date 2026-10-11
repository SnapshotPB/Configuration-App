@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.bluetooth.emulator

import com.snapshotpb.configuration.bluetooth.items
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.Promise
import kotlin.js.toInt
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array

// ---- `window.snapshotBoard`, version 1 (Kotlin/Wasm external declarations) ----

internal external interface SnapshotBoardJs : JsAny {
    fun scan(): AdvertisementJs?
    fun connect(mtu: Int): Promise<JsNumber>
    fun disconnect(): Promise<JsAny?>
    fun isConnected(): Boolean
    fun gatt(): JsArray<ServiceJs>
    fun read(handle: Int, offset: Int): Promise<ReadResultJs>
    fun write(handle: Int, value: Uint8Array, withResponse: Boolean): Promise<JsNumber>
    fun subscribe(handle: Int, mode: Int): Promise<JsNumber>
}

internal external interface AdvertisementJs : JsAny {
    val id: String
    val name: String?
}

internal external interface ServiceJs : JsAny {
    val uuid: String
    val characteristics: JsArray<CharacteristicJs>
}

internal external interface CharacteristicJs : JsAny {
    val uuid: String
    val handle: Int
    val properties: Int
    val ccc: JsNumber?
}

internal external interface ReadResultJs : JsAny {
    val status: Int
    val value: Uint8Array
}

/** One event of the bridge. Only the fields of its [type] are present. */
internal external interface BoardEventJs : JsAny {
    val type: String
    val handle: Int
    val value: Uint8Array
    val reason: Int
    val on: Boolean
}

/**
 * The bridge on the parent window of [target], when [target] is a frame inside that window and the
 * bridge has version 1. Otherwise null. A cross-origin parent throws on access, which gives null.
 */
internal fun viewerBoardOf(target: JsAny?): SnapshotBoardJs? = js(
    """(() => {
        try {
            if (!target || !target.parent || target.parent === target) return null;
            const board = target.parent.snapshotBoard;
            return board && board.version === 1 ? board : null;
        } catch (e) {
            return null;
        }
    })()"""
)

private fun currentWindow(): JsAny? = js("(typeof window !== 'undefined') ? window : null")

/** The bridge of the viewer that holds this page in an iframe, or null. */
internal fun viewerBoard(): SnapshotBoardJs? = viewerBoardOf(currentWindow())

private fun addBoardListener(board: SnapshotBoardJs, callback: (BoardEventJs) -> Unit): JsAny =
    js("board.addEventListener((event) => callback(event))")

private fun callFunction(callable: JsAny): Unit = js("callable()")

// ---- adapter ----

/** Adapts the JavaScript bridge to [BoardBridge]: it awaits each Promise and copies each byte array. */
internal class JsBoardBridge(private val board: SnapshotBoardJs) : BoardBridge {
    override fun scan(): BoardAdvertisement? =
        board.scan()?.let { advertisement -> BoardAdvertisement(advertisement.id, advertisement.name) }

    override suspend fun connect(mtu: Int): Int = board.connect(mtu).await<JsNumber>().toInt()

    override suspend fun disconnect() {
        board.disconnect().await<JsAny?>()
    }

    override fun isConnected(): Boolean = board.isConnected()

    override fun gatt(): List<BoardService> =
        board.gatt().items().map { service ->
            BoardService(
                uuid = service.uuid,
                characteristics = service.characteristics.items().map { characteristic ->
                    BoardCharacteristic(
                        uuid = characteristic.uuid,
                        handle = characteristic.handle,
                        properties = characteristic.properties,
                        ccc = characteristic.ccc?.toInt(),
                    )
                },
            )
        }

    override suspend fun read(handle: Int, offset: Int): BoardReadResult {
        val result = board.read(handle, offset).await<ReadResultJs>()
        return BoardReadResult(result.status, result.value.toByteArray())
    }

    override suspend fun write(handle: Int, value: ByteArray, withResponse: Boolean): Int =
        board.write(handle, value.asUByteArray().toUint8Array(), withResponse).await<JsNumber>().toInt()

    override suspend fun subscribe(handle: Int, mode: Int): Int =
        board.subscribe(handle, mode).await<JsNumber>().toInt()

    override fun addEventListener(listener: (BoardEvent) -> Unit): () -> Unit {
        val remove = addBoardListener(board) { event -> event.toBoardEvent()?.let(listener) }
        return { callFunction(remove) }
    }
}

/** The Kotlin event for [this], or null for a type that version 1 does not define. */
private fun BoardEventJs.toBoardEvent(): BoardEvent? =
    when (type) {
        "notify", "indicate" -> BoardEvent.Value(handle, value.toByteArray())
        "disconnected" -> BoardEvent.Disconnected(reason)
        "advertising" -> BoardEvent.Advertising(on)
        else -> null
    }

private fun Uint8Array.toByteArray(): ByteArray = toUByteArray().asByteArray()
