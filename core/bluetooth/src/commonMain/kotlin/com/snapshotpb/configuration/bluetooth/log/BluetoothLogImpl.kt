package com.snapshotpb.configuration.bluetooth.log

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * In-memory implementation of [BluetoothLog].
 *
 * The engine assigns session numbers and passes them to each call.
 * Every call puts its input into one unbounded channel in call order without suspension.
 * Board chunks obey a 128 KiB queue limit.
 * [boardChunk] drops a chunk above that limit and adds one to `droppedChunks`.
 *
 * One coroutine reads the channel and owns all mutable state.
 * Control calls publish [snapshot] at once.
 * A board chunk or an app event publishes at once after 250 ms since the last publish.
 * A burst gives one publish at its start and one when the 250 ms interval ends.
 * The end of the interval sends a tick input through the same queue, so no input is lost.
 *
 * Retention keeps at most 2,000 entries and 262,144 text characters, with each entry cut to
 * 512 characters after redaction.
 * The memory budget is about 1 MB in UTF-16 for entries in the worst case, plus 128 KiB for
 * queued chunks.
 */
class BluetoothLogImpl(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : BluetoothLog {

    private val _snapshot = MutableStateFlow(BluetoothLogSnapshot())
    override val snapshot: StateFlow<BluetoothLogSnapshot> = _snapshot.asStateFlow()

    override val recordsBoardLines: Boolean = true

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val pendingChunkBytes = MutableStateFlow(0)
    private val droppedChunks = MutableStateFlow(0L)

    // Consumer-owned state: only the consumer coroutine reads or writes these fields.
    private val entries = ArrayDeque<BluetoothLogEntry>()
    private var totalTextChars = 0
    private val openSessions = mutableMapOf<Int, OpenSession>()
    private var newestSession = 0
    private val boardLabels = mutableMapOf<String, String>()
    private var nextSequence = 1L
    private var droppedAtClear = 0L
    private var lastPublishMillis: Long? = null
    private var dirty = false
    private var tickPending = false

    init {
        scope.launch {
            runConsumer()
        }
    }

    override fun startSession(session: Int) {
        inputs.trySend(Input.StartSession(session = session, timeMillis = nowMillis()))
    }

    override fun sessionBoard(session: Int, boardId: String, firmwareVersion: String?) {
        inputs.trySend(
            Input.SessionBoard(
                session = session,
                boardId = boardId,
                firmwareVersion = firmwareVersion,
                timeMillis = nowMillis(),
            ),
        )
    }

    override fun boardChunk(session: Int, bytes: ByteArray) {
        val size = bytes.size
        // The check and the add are two steps, so concurrent calls can pass the limit by a few chunks.
        if (pendingChunkBytes.value.toLong() + size > MAX_PENDING_CHUNK_BYTES) {
            droppedChunks.update { it + 1L }
            return
        }
        pendingChunkBytes.update { it + size }
        val result = inputs.trySend(
            Input.BoardChunk(session = session, bytes = bytes.copyOf(), timeMillis = nowMillis()),
        )
        if (result.isFailure) {
            pendingChunkBytes.update { (it - size).coerceAtLeast(0) }
        }
    }

    override fun boardLogState(session: Int, state: BoardLogState) {
        inputs.trySend(Input.BoardLogStateChange(session = session, state = state, timeMillis = nowMillis()))
    }

    override fun app(session: Int, event: BluetoothLogEvent, detail: String?) {
        inputs.trySend(
            Input.AppEvent(
                session = session,
                event = event,
                detail = detail,
                timeMillis = nowMillis(),
            ),
        )
    }

    override fun endSession(session: Int) {
        inputs.trySend(Input.EndSession(session = session, timeMillis = nowMillis()))
    }

    override fun clear() {
        inputs.trySend(Input.Clear(timeMillis = nowMillis()))
    }

    private suspend fun runConsumer() {
        while (true) {
            handleSafely(inputs.receive())
        }
    }

    private fun handleSafely(input: Input) {
        runCatching { handle(input) }.onFailure { error ->
            if (error is CancellationException) throw error
            log.e { error::class.simpleName ?: "Throwable" }
        }
    }

    private fun handle(input: Input) {
        when (input) {
            is Input.StartSession -> handleStartSession(input)
            is Input.SessionBoard -> handleSessionBoard(input)
            is Input.BoardChunk -> handleBoardChunk(input)
            is Input.BoardLogStateChange -> handleBoardLogState(input)
            is Input.AppEvent -> handleAppEvent(input)
            is Input.EndSession -> handleEndSession(input)
            is Input.Clear -> handleClear()
            is Input.Tick -> handleTick()
        }
    }

    private fun handleStartSession(input: Input.StartSession) {
        if (input.session in openSessions) return
        openSessions[input.session] = OpenSession()
        if (input.session > newestSession) {
            newestSession = input.session
        }
        addEntry(
            timeMillis = input.timeMillis,
            session = input.session,
            source = BluetoothLogSource.APP,
            rawText = "session ${input.session} started",
        )
        publish()
    }

    private fun handleSessionBoard(input: Input.SessionBoard) {
        val open = openSessions[input.session] ?: return
        val label = boardLabels.getOrPut(input.boardId) { "board ${boardLabels.size + 1}" }
        val version = input.firmwareVersion
            ?.let { BluetoothLogRedactor.clean(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val header = "session ${input.session}: $label, firmware $version"
        if (header == open.lastBoardHeader) return
        open.lastBoardHeader = header
        addEntry(
            timeMillis = input.timeMillis,
            session = input.session,
            source = BluetoothLogSource.APP,
            rawText = header,
        )
        publish()
    }

    private fun handleBoardChunk(input: Input.BoardChunk) {
        try {
            val open = openSessions[input.session] ?: return
            open.boardLogState = BoardLogState.RECEIVING
            val lines = open.assembler.append(input.bytes)
            for (line in lines) {
                addEntry(
                    timeMillis = input.timeMillis,
                    session = input.session,
                    source = BluetoothLogSource.BOARD,
                    rawText = line,
                )
            }
            publishOrDefer()
        } finally {
            pendingChunkBytes.update { (it - input.bytes.size).coerceAtLeast(0) }
        }
    }

    private fun handleBoardLogState(input: Input.BoardLogStateChange) {
        val open = openSessions[input.session] ?: return
        val ignored = input.state == BoardLogState.REQUESTED && open.boardLogState == BoardLogState.RECEIVING
        if (!ignored && open.boardLogState != input.state) {
            open.boardLogState = input.state
            when (input.state) {
                BoardLogState.REQUESTED,
                BoardLogState.ABSENT,
                BoardLogState.NOT_VISIBLE,
                BoardLogState.FAILED,
                -> addEntry(
                    timeMillis = input.timeMillis,
                    session = input.session,
                    source = BluetoothLogSource.APP,
                    rawText = "session ${input.session}: board log ${input.state.name.lowercase()}",
                )
                BoardLogState.NO_SESSION,
                BoardLogState.RECEIVING,
                -> Unit
            }
        }
        publish()
    }

    private fun handleAppEvent(input: Input.AppEvent) {
        val cleanedDetail = input.detail
            ?.let { BluetoothLogRedactor.clean(it) }
            ?.takeIf { it.isNotBlank() }
        val text = if (cleanedDetail != null) {
            "${input.event.code} $cleanedDetail"
        } else {
            input.event.code
        }
        addEntry(
            timeMillis = input.timeMillis,
            session = input.session,
            source = BluetoothLogSource.APP,
            rawText = text,
        )
        publishOrDefer()
    }

    private fun handleEndSession(input: Input.EndSession) {
        val open = openSessions.remove(input.session) ?: return
        val partLine = open.assembler.flush()
        if (partLine != null) {
            addEntry(
                timeMillis = input.timeMillis,
                session = input.session,
                source = BluetoothLogSource.BOARD,
                rawText = partLine,
            )
        }
        addEntry(
            timeMillis = input.timeMillis,
            session = input.session,
            source = BluetoothLogSource.APP,
            rawText = "session ${input.session} ended",
        )
        publish()
    }

    private fun handleClear() {
        entries.clear()
        totalTextChars = 0
        droppedAtClear = droppedChunks.value
        publish()
    }

    private fun handleTick() {
        tickPending = false
        if (dirty) {
            publish()
        }
    }

    private fun addEntry(
        timeMillis: Long,
        session: Int,
        source: BluetoothLogSource,
        rawText: String,
    ) {
        val text = BluetoothLogRedactor.clean(rawText).take(MAX_ENTRY_CHARS)
        val entry = BluetoothLogEntry(
            sequence = nextSequence,
            timeMillis = timeMillis,
            session = session,
            source = source,
            text = text,
        )
        nextSequence += 1L
        entries.addLast(entry)
        totalTextChars += text.length
        while (entries.size > MAX_ENTRIES || totalTextChars > MAX_TEXT_CHARS) {
            val removed = entries.removeFirst()
            totalTextChars -= removed.text.length
        }
    }

    private fun publish() {
        val state = openSessions[newestSession]?.boardLogState ?: BoardLogState.NO_SESSION
        val dropped = (droppedChunks.value - droppedAtClear).coerceAtLeast(0L)
        _snapshot.value = BluetoothLogSnapshot(
            entries = entries.toList(),
            boardLogState = state,
            droppedChunks = dropped,
        )
        lastPublishMillis = nowMillis()
        dirty = false
    }

    private fun publishOrDefer() {
        val last = lastPublishMillis
        val elapsed = if (last == null) PUBLISH_INTERVAL_MILLIS else nowMillis() - last
        if (elapsed >= PUBLISH_INTERVAL_MILLIS) {
            publish()
        } else {
            dirty = true
            if (!tickPending) {
                val waitMillis = (PUBLISH_INTERVAL_MILLIS - elapsed).coerceIn(1L, PUBLISH_INTERVAL_MILLIS)
                tickPending = true
                scope.launch {
                    delay(waitMillis)
                    inputs.trySend(Input.Tick(timeMillis = nowMillis()))
                }
            }
        }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private class OpenSession {
        val assembler: BoardLogLineAssembler = BoardLogLineAssembler()
        var boardLogState: BoardLogState = BoardLogState.NO_SESSION
        var lastBoardHeader: String? = null
    }

    private sealed interface Input {
        val timeMillis: Long

        class StartSession(val session: Int, override val timeMillis: Long) : Input
        class SessionBoard(
            val session: Int,
            val boardId: String,
            val firmwareVersion: String?,
            override val timeMillis: Long,
        ) : Input

        class BoardChunk(val session: Int, val bytes: ByteArray, override val timeMillis: Long) : Input
        class BoardLogStateChange(
            val session: Int,
            val state: BoardLogState,
            override val timeMillis: Long,
        ) : Input

        class AppEvent(
            val session: Int,
            val event: BluetoothLogEvent,
            val detail: String?,
            override val timeMillis: Long,
        ) : Input

        class EndSession(val session: Int, override val timeMillis: Long) : Input
        class Clear(override val timeMillis: Long) : Input
        class Tick(override val timeMillis: Long) : Input
    }

    private companion object {
        const val MAX_PENDING_CHUNK_BYTES: Int = 131_072
        const val MAX_ENTRY_CHARS: Int = 512
        const val MAX_ENTRIES: Int = 2_000
        const val MAX_TEXT_CHARS: Int = 262_144
        const val PUBLISH_INTERVAL_MILLIS: Long = 250L

        val log = Arbor.tag("BluetoothLogImpl")
    }
}
