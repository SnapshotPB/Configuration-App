package com.snapshotpb.configuration.bluetooth.ots

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkAccumulatorTest {

    @Test
    fun exactSizeSingleChunkCompletes() {
        val acc = ChunkAccumulator(expectedSize = 4)
        acc.append(byteArrayOf(1, 2, 3, 4))

        assertTrue(acc.isComplete)
        assertTrue(acc.isDone)
        assertFalse(acc.isOverrun)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), acc.result())
    }

    @Test
    fun multiChunkReassemblesInOrder() {
        val acc = ChunkAccumulator(expectedSize = 6)
        acc.append(byteArrayOf(0x0A))
        assertFalse(acc.isDone)
        acc.append(byteArrayOf(0x0B, 0x0C))
        assertFalse(acc.isDone)
        acc.append(byteArrayOf(0x0D, 0x0E, 0x0F))

        assertTrue(acc.isComplete)
        assertContentEquals(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F), acc.result())
    }

    @Test
    fun truncatedTransferThrowsTransferIncomplete() {
        val acc = ChunkAccumulator(expectedSize = 8)
        acc.append(byteArrayOf(1, 2, 3))

        assertFalse(acc.isComplete)
        assertFalse(acc.isDone)
        val ex = assertFailsWith<OtsTransferIncomplete> { acc.result() }
        assertEquals(8L, ex.expected)
        assertEquals(3, ex.received)
    }

    @Test
    fun emptyObjectIsImmediatelyComplete() {
        val acc = ChunkAccumulator(expectedSize = 0)
        assertTrue(acc.isComplete)
        assertTrue(acc.isDone)
        assertContentEquals(ByteArray(0), acc.result())
    }

    @Test
    fun overrunIsDetected() {
        val acc = ChunkAccumulator(expectedSize = 2)
        acc.append(byteArrayOf(1, 2, 3))

        assertTrue(acc.isDone)
        assertTrue(acc.isOverrun)
        assertFalse(acc.isComplete)
        assertFailsWith<OtsTransferIncomplete> { acc.result() }
    }

    // --- Fake notification-flow tests -----------------------------------------
    //
    // These mirror the collect loop in OtsClient.readSelectedObjectBytes:
    // an UNDISPATCHED collector accumulates chunks from a SharedFlow and
    // signals completion via a CompletableDeferred; the caller awaits with a
    // timeout. They exercise the size-completion logic end-to-end without a
    // live GATT connection.

    private suspend fun collectUntilDone(
        expectedSize: Long,
        notifications: Flow<ByteArray>,
        timeoutMillis: Long,
        emit: suspend () -> Unit,
    ): ByteArray {
        val accumulator = ChunkAccumulator(expectedSize)
        if (expectedSize == 0L) return accumulator.result()

        val complete = CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                notifications.collect { chunk ->
                    accumulator.append(chunk)
                    if (accumulator.isDone) {
                        complete.complete(Unit)
                        return@collect
                    }
                }
            }
            emit()
            val finished = withTimeoutOrNull(timeoutMillis) {
                complete.await()
                true
            } ?: false
            collector.cancelAndJoin()
            if (!finished || !accumulator.isComplete) {
                throw OtsTransferIncomplete(expectedSize, accumulator.received)
            }
        }
        return accumulator.result()
    }

    @Test
    fun fakeFlowExactSizeCompletes() = runTest {
        val flow = MutableSharedFlow<ByteArray>()
        val result = collectUntilDone(expectedSize = 3, notifications = flow, timeoutMillis = 5_000) {
            flow.emit(byteArrayOf(7, 8, 9))
        }
        assertContentEquals(byteArrayOf(7, 8, 9), result)
    }

    @Test
    fun fakeFlowMultiChunkReassembly() = runTest {
        val flow = MutableSharedFlow<ByteArray>()
        val result = collectUntilDone(expectedSize = 5, notifications = flow, timeoutMillis = 5_000) {
            flow.emit(byteArrayOf(1, 2))
            flow.emit(byteArrayOf(3))
            flow.emit(byteArrayOf(4, 5))
        }
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result)
    }

    @Test
    fun fakeFlowTruncationThrows() = runTest {
        val flow = MutableSharedFlow<ByteArray>()
        val ex = assertFailsWith<OtsTransferIncomplete> {
            collectUntilDone(expectedSize = 10, notifications = flow, timeoutMillis = 5_000) {
                flow.emit(byteArrayOf(1, 2, 3))
                // Firmware stops short — no further chunks; the timeout fires.
            }
        }
        assertEquals(10L, ex.expected)
        assertEquals(3, ex.received)
    }

    @Test
    fun fakeFlowTimeoutWithNoChunksThrows() = runTest {
        val flow = MutableSharedFlow<ByteArray>()
        val ex = assertFailsWith<OtsTransferIncomplete> {
            collectUntilDone(expectedSize = 4, notifications = flow, timeoutMillis = 1_000) {
                // Firmware never notifies anything.
            }
        }
        assertEquals(0, ex.received)
    }
}
