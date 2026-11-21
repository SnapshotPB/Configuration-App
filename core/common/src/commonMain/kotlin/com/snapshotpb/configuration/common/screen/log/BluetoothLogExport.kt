package com.snapshotpb.configuration.common.screen.log

import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSource
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val NANOS_PER_MILLISECOND = 1_000_000
private const val FILE_NAME_LENGTH = 44
private const val CLOCK_TIME_LENGTH = 12

/** Makes the text and the file name for a Bluetooth log export. */
object BluetoothLogExport {
    /** The text of the export file. */
    fun format(snapshot: BluetoothLogSnapshot, appVersion: String, now: Instant): String = buildString {
        append("SnapshotPB Bluetooth log\n")
        append("App version: ")
        append(appVersion)
        append('\n')
        append("Exported: ")
        appendUtcIsoTime(now)
        append('\n')
        append("Board log state: ")
        append(snapshot.boardLogState.name)
        append('\n')
        append("Entries: ")
        append(snapshot.entries.size)
        append('\n')
        append("Chunks not recorded: ")
        append(snapshot.droppedChunks)
        append('\n')
        append("Columns: UTC time, session, source, text.\n")
        append('\n')
        if (snapshot.entries.isEmpty()) {
            append("No entries.\n")
        } else {
            for (entry in snapshot.entries) {
                appendUtcIsoTime(Instant.fromEpochMilliseconds(entry.timeMillis))
                append(" s")
                append(entry.session)
                append(' ')
                append(
                    when (entry.source) {
                        BluetoothLogSource.BOARD -> "board"
                        BluetoothLogSource.APP -> "app  "
                    }
                )
                append(' ')
                append(entry.text)
                append('\n')
            }
        }
    }

    /** `snapshotpb-bluetooth-log-<yyyyMMdd-HHmmss>.txt`, in UTC. */
    fun fileName(now: Instant): String = buildString(FILE_NAME_LENGTH) {
        val dateTime = now.toLocalDateTime(TimeZone.UTC)
        append("snapshotpb-bluetooth-log-")
        appendZeroPadded(dateTime.year, 4)
        appendZeroPadded(dateTime.month.number, 2)
        appendZeroPadded(dateTime.day, 2)
        append('-')
        appendZeroPadded(dateTime.hour, 2)
        appendZeroPadded(dateTime.minute, 2)
        appendZeroPadded(dateTime.second, 2)
        append(".txt")
    }
}

/** The wall-clock time of an entry as `HH:mm:ss.SSS` in [timeZone]. The screen shows it. */
internal fun bluetoothLogClockTime(timeMillis: Long, timeZone: TimeZone): String = buildString(CLOCK_TIME_LENGTH) {
    val dateTime = Instant.fromEpochMilliseconds(timeMillis).toLocalDateTime(timeZone)
    appendClockTime(dateTime)
}

private fun StringBuilder.appendUtcIsoTime(instant: Instant) {
    val dateTime = instant.toLocalDateTime(TimeZone.UTC)
    appendZeroPadded(dateTime.year, 4)
    append('-')
    appendZeroPadded(dateTime.month.number, 2)
    append('-')
    appendZeroPadded(dateTime.day, 2)
    append('T')
    appendClockTime(dateTime)
    append('Z')
}

private fun StringBuilder.appendClockTime(dateTime: LocalDateTime) {
    appendZeroPadded(dateTime.hour, 2)
    append(':')
    appendZeroPadded(dateTime.minute, 2)
    append(':')
    appendZeroPadded(dateTime.second, 2)
    append('.')
    appendZeroPadded(dateTime.nanosecond / NANOS_PER_MILLISECOND, 3)
}

private fun StringBuilder.appendZeroPadded(value: Int, width: Int) {
    val text = value.toString()
    repeat((width - text.length).coerceAtLeast(0)) {
        append('0')
    }
    append(text)
}
