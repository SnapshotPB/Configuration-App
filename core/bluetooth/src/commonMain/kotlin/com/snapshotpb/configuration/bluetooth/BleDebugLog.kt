package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BleDebugEvent(
    val timestampMillis: Long,
    val deviceId: String,
    val characteristicUuid: String,
    val bytes: ByteArray,
) {
    val hex: String
        get() = bytes.joinToString(separator = " ") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    val text: String get() = bytes.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDebugEvent) return false
        return timestampMillis == other.timestampMillis &&
            deviceId == other.deviceId &&
            characteristicUuid == other.characteristicUuid &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = timestampMillis.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * In-memory ring buffer of inbound BLE traffic, captured when a device connects so the
 * raw protocol exchange can be inspected while debugging.
 */
class BleDebugLog {
    private val _events = MutableStateFlow<List<BleDebugEvent>>(emptyList())
    val events: StateFlow<List<BleDebugEvent>> = _events

    fun record(event: BleDebugEvent) {
        _events.update { current -> (current + event).takeLast(MAX_EVENTS) }
    }

    fun clear(deviceId: String) {
        _events.update { current -> current.filterNot { it.deviceId == deviceId } }
    }

    fun clear() {
        _events.value = emptyList()
    }

    companion object {
        private const val MAX_EVENTS = 200
    }
}