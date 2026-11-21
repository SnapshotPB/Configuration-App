package com.snapshotpb.configuration.bluetooth.log

/** Joins the NUS notifications of one session into log lines. Not thread-safe: one owner only. */
internal class BoardLogLineAssembler {
    private var buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
    private var bufferSize = 0

    /** Adds one notification. Returns the lines that it completes, in order, with no CR and no LF. */
    fun append(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        if (bufferSize > 0 && startsNewLine(chunk)) {
            lines.add(closePartLine())
        }

        val scanStart = bufferSize
        appendToBuffer(chunk)

        var segmentStart = 0
        for (index in scanStart until bufferSize) {
            if (buffer[index] == LF_BYTE) {
                var segmentEnd = index
                if (segmentEnd > segmentStart && buffer[segmentEnd - 1] == CR_BYTE) {
                    segmentEnd -= 1
                }
                if (segmentEnd > segmentStart) {
                    lines.add(buffer.decodeToString(startIndex = segmentStart, endIndex = segmentEnd))
                }
                segmentStart = index + 1
            }
        }

        if (segmentStart > 0) {
            val restSize = bufferSize - segmentStart
            if (restSize > 0) {
                buffer.copyInto(
                    destination = buffer,
                    destinationOffset = 0,
                    startIndex = segmentStart,
                    endIndex = bufferSize,
                )
                bufferSize = restSize
            } else {
                resetBuffer()
            }
        }

        if (bufferSize > MAX_PART_LINE_BYTES) {
            lines.add(closePartLine())
        }

        return lines
    }

    /** Returns the open part line with [CUT_SUFFIX], or null when no part line is open. Empties the buffer. */
    fun flush(): String? {
        if (bufferSize == 0) return null
        return closePartLine()
    }

    private fun closePartLine(): String {
        val line = buffer.decodeToString(startIndex = 0, endIndex = bufferSize) + CUT_SUFFIX
        resetBuffer()
        return line
    }

    private fun appendToBuffer(chunk: ByteArray) {
        val needed = bufferSize + chunk.size
        if (needed > buffer.size) {
            val newCapacity = maxOf(needed, buffer.size * 2)
            buffer = buffer.copyOf(newCapacity)
        }
        chunk.copyInto(destination = buffer, destinationOffset = bufferSize)
        bufferSize = needed
    }

    private fun resetBuffer() {
        bufferSize = 0
        if (buffer.size > MAX_PART_LINE_BYTES) {
            buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
        }
    }

    private fun startsNewLine(chunk: ByteArray): Boolean {
        if (startsWithDroppedPrefix(chunk)) return true
        if (chunk[0] != OPEN_BRACKET_BYTE) return false
        val prefixLength = minOf(chunk.size, NEW_LINE_PREFIX_BYTES)
        val prefix = chunk.decodeToString(startIndex = 0, endIndex = prefixLength)
        return TIMESTAMP_PREFIX.containsMatchIn(prefix)
    }

    private fun startsWithDroppedPrefix(chunk: ByteArray): Boolean {
        if (chunk.size < DROPPED_PREFIX_BYTES.size) return false
        for (index in DROPPED_PREFIX_BYTES.indices) {
            if (chunk[index] != DROPPED_PREFIX_BYTES[index]) return false
        }
        return true
    }

    companion object {
        /** The suffix of a line that lost its end. */
        const val CUT_SUFFIX: String = " …"

        /** The maximum size of an open part line. The board sends a maximum of 495 bytes in one line. */
        const val MAX_PART_LINE_BYTES: Int = 2048

        private const val INITIAL_BUFFER_CAPACITY: Int = 256
        private const val NEW_LINE_PREFIX_BYTES: Int = 32
        private const val LF_BYTE: Byte = 0x0A
        private const val CR_BYTE: Byte = 0x0D
        private const val OPEN_BRACKET_BYTE: Byte = '['.code.toByte()

        private val DROPPED_PREFIX_BYTES: ByteArray = "--- ".encodeToByteArray()
        private val TIMESTAMP_PREFIX: Regex = Regex("""^\[\d{2,}:\d{2}:\d{2}\.\d{3},\d{3}\] """)
    }
}
