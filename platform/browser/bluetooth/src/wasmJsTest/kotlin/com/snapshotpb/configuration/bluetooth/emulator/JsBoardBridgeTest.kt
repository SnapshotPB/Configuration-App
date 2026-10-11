@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.bluetooth.emulator

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of [JsBoardBridge] and [viewerBoardOf] against JavaScript objects with the shape of
 * `window.snapshotBoard` version 1. They check the interop: the Promises, the byte arrays, the
 * GATT table, the events and the detection of the viewer.
 */
class JsBoardBridgeTest {

    @Test
    fun scan_givesTheIdAndTheName() {
        val advertisement = assertNotNull(JsBoardBridge(fakeBoard()).scan())

        assertEquals("5350425f4f5440008000000000000001", advertisement.id)
        assertEquals("Snapshot PB", advertisement.name)
    }

    @Test
    fun connect_awaitsTheStatusAndSendsTheMtu() = runTest {
        val board = fakeBoard()
        val bridge = JsBoardBridge(board)

        assertEquals(0, bridge.connect(517))
        assertEquals(517, requestedMtu(board))
        assertTrue(bridge.isConnected())

        bridge.disconnect()
        assertFalse(bridge.isConnected())
    }

    @Test
    fun gatt_givesTheHandlesThePropertiesAndTheCcc() {
        val service = JsBoardBridge(fakeBoard()).gatt().single()

        assertEquals("00001825-0000-1000-8000-00805f9b34fb", service.uuid)
        val (oacp, name) = service.characteristics
        assertEquals("00002ac5-0000-1000-8000-00805f9b34fb", oacp.uuid)
        assertEquals(3, oacp.handle)
        assertEquals(GattProperty.WRITE or GattProperty.INDICATE, oacp.properties)
        assertEquals(4, oacp.ccc)
        assertNull(name.ccc)
    }

    @Test
    fun read_givesTheStatusAndTheBytes() = runTest {
        val result = JsBoardBridge(fakeBoard()).read(handle = 9, offset = 64)

        assertEquals(0, result.status)
        // 200 does not fit in a signed byte, so it comes back as -56.
        assertContentEquals(byteArrayOf(9, 64, -56), result.value)
    }

    @Test
    fun write_sendsTheBytesAndGivesTheStatus() = runTest {
        val board = fakeBoard()
        val bridge = JsBoardBridge(board)

        assertEquals(0, bridge.write(handle = 3, value = byteArrayOf(1, -1), withResponse = true))
        assertEquals(
            ATT_INVALID_ATTRIBUTE_VALUE_LENGTH,
            bridge.write(handle = 3, value = ByteArray(63), withResponse = false),
        )

        assertEquals("""{"handle":3,"value":[1,255],"withResponse":true}""", firstWrite(board))
    }

    @Test
    fun subscribe_givesTheStatus() = runTest {
        val bridge = JsBoardBridge(fakeBoard())

        assertEquals(0, bridge.subscribe(handle = 3, mode = 2))
        assertEquals(ATT_REQUEST_NOT_SUPPORTED, bridge.subscribe(handle = 3, mode = 1))
    }

    @Test
    fun events_becomeBoardEventsUntilTheListenerIsRemoved() {
        val board = fakeBoard()
        val events = mutableListOf<BoardEvent>()
        val remove = JsBoardBridge(board).addEventListener { events += it }

        emit(board, valueEvent("notify"))
        emit(board, valueEvent("indicate"))
        emit(board, disconnectedEvent())
        emit(board, advertisingEvent())
        emit(board, unknownEvent())
        remove()
        emit(board, advertisingEvent())

        assertEquals(4, events.size)
        val notification = assertIs<BoardEvent.Value>(events[0])
        assertEquals(3, notification.handle)
        assertContentEquals(byteArrayOf(1, -1), notification.value)
        assertIs<BoardEvent.Value>(events[1])
        assertEquals(0x13, assertIs<BoardEvent.Disconnected>(events[2]).reason)
        assertTrue(assertIs<BoardEvent.Advertising>(events[3]).on)
        assertEquals(0, listenerCount(board))
    }

    @Test
    fun viewerBoardOf_findsVersion1OnTheParentOfAFrame() {
        assertNotNull(viewerBoardOf(frameIn(parentWith(boardWithVersion(1)))))
    }

    @Test
    fun viewerBoardOf_givesNullOutsideTheViewer() {
        // Another version, no bridge, a top-level window, and a cross-origin parent.
        assertNull(viewerBoardOf(frameIn(parentWith(boardWithVersion(2)))))
        assertNull(viewerBoardOf(frameIn(parentWith(null))))
        assertNull(viewerBoardOf(topWindowWithBoard()))
        assertNull(viewerBoardOf(crossOriginFrame()))
        assertNull(viewerBoardOf(null))
    }

    private companion object {
        const val ATT_REQUEST_NOT_SUPPORTED = 0x06
        const val ATT_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D
    }
}

/** A bridge with the shape of version 1. A write of more than 62 bytes fails, as with an MTU of 65. */
private fun fakeBoard(): SnapshotBoardJs = js(
    """({
        version: 1,
        connected: false,
        mtu: 0,
        listeners: [],
        written: [],
        scan() {
            return {
                id: '5350425f4f5440008000000000000001',
                name: 'Snapshot PB',
                rssi: -40,
                adv: new Uint8Array([2, 1, 6]),
                scanResponse: new Uint8Array(0),
            };
        },
        connect(mtu) { this.mtu = mtu; this.connected = true; return Promise.resolve(0); },
        disconnect() { this.connected = false; return Promise.resolve(); },
        isConnected() { return this.connected; },
        gatt() {
            return [{
                uuid: '00001825-0000-1000-8000-00805f9b34fb',
                characteristics: [
                    { uuid: '00002ac5-0000-1000-8000-00805f9b34fb', handle: 3, properties: 0x28, ccc: 4 },
                    { uuid: '00002abe-0000-1000-8000-00805f9b34fb', handle: 9, properties: 0x02, ccc: null },
                ],
            }];
        },
        read(handle, offset) { return Promise.resolve({ status: 0, value: new Uint8Array([handle, offset, 200]) }); },
        write(handle, value, withResponse) {
            this.written.push({ handle: handle, value: Array.from(value), withResponse: withResponse });
            return Promise.resolve(value.length > 62 ? 0x0d : 0);
        },
        subscribe(handle, mode) { return Promise.resolve(mode === 2 ? 0 : 0x06); },
        addEventListener(callback) {
            this.listeners.push(callback);
            return () => { this.listeners = this.listeners.filter((listener) => listener !== callback); };
        },
    })"""
)

private fun emit(board: SnapshotBoardJs, event: JsAny): Unit =
    js("board.listeners.forEach((listener) => listener(event))")

private fun requestedMtu(board: SnapshotBoardJs): Int = js("board.mtu")

private fun firstWrite(board: SnapshotBoardJs): String = js("JSON.stringify(board.written[0])")

private fun listenerCount(board: SnapshotBoardJs): Int = js("board.listeners.length")

private fun valueEvent(type: String): JsAny = js("({ type: type, handle: 3, value: new Uint8Array([1, 255]) })")

private fun disconnectedEvent(): JsAny = js("({ type: 'disconnected', reason: 0x13 })")

private fun advertisingEvent(): JsAny = js("({ type: 'advertising', on: true })")

private fun unknownEvent(): JsAny = js("({ type: 'rssi', rssi: -50 })")

private fun boardWithVersion(version: Int): JsAny = js("({ version: version })")

private fun parentWith(board: JsAny?): JsAny = js("({ snapshotBoard: board })")

private fun frameIn(parent: JsAny): JsAny = js("({ parent: parent })")

private fun topWindowWithBoard(): JsAny =
    js("(() => { const top = { snapshotBoard: { version: 1 } }; top.parent = top; return top; })()")

private fun crossOriginFrame(): JsAny = js("({ get parent() { throw new Error('SecurityError'); } })")
