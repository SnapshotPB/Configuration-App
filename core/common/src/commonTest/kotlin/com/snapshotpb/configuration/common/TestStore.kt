package com.snapshotpb.configuration.common

import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.StoreIOProvider
import com.snapshotpb.configuration.store.appJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/** In-memory [StoreIOProvider] so these tests never touch a filesystem. */
class InMemoryStoreIO(initial: Map<String, String> = emptyMap()) : StoreIOProvider {
    val content: MutableMap<String, ByteArray> =
        initial.mapValues { (_, v) -> v.encodeToByteArray() }.toMutableMap()

    override fun exists(storeName: String): Boolean = content.containsKey(storeName)

    override fun sinkFor(storeName: String): RawSink = object : RawSink {
        private val buffered = Buffer()
        override fun write(source: Buffer, byteCount: Long) = source.readTo(buffered, byteCount)
        override fun flush() { content[storeName] = buffered.copy().readByteArray() }
        override fun close() = flush()
    }

    override fun sourceFor(storeName: String): RawSource =
        Buffer().apply { write(content[storeName] ?: ByteArray(0)) }

    override fun sourceForUri(uri: String): RawSource = Buffer()

    override fun pathFor(storeName: String): String = "/fake/$storeName"

    override fun delete(storeName: String) {
        content.remove(storeName)
    }
}

fun testDeviceStore(
    dispatcher: CoroutineDispatcher,
    devices: List<Device> = emptyList(),
): DeviceStore = DeviceStore(
    storeIOProvider = InMemoryStoreIO(
        if (devices.isEmpty()) emptyMap() else mapOf("devices.json" to appJson.encodeToString(devices))
    ),
    json = appJson,
    dispatcher = dispatcher,
)

val testDeviceId: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
val testProfileId: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555556")

fun testProfile(
    id: Uuid = testProfileId,
    name: String = "P0",
    eyeThreshold: Int = 50,
    searSolenoidDwell: Int = 45,
): MarkerProfile = MarkerProfile(
    id = id,
    name = name,
    eyeThreshold = eyeThreshold,
    searSolenoidDwell = searSolenoidDwell,
)

fun testDevice(
    id: Uuid = testDeviceId,
    name: String = "Marker",
    bootText: String = "SnapshotPB",
    profiles: List<MarkerProfile> = listOf(testProfile()),
    boardState: com.snapshotpb.configuration.model.BoardState? = null,
    language: BoardLanguage? = null,
    screenBrightness: BoardScreenBrightness? = null,
    screenOffDelay: Int? = null,
    displayWindow: com.snapshotpb.configuration.model.DisplayWindow? = null,
    boltMode: MarkerBoltMode? = null,
): Device = Device(
    id = id,
    name = name,
    lastSeen = LocalDate(2026, 7, 26),
    knownProfiles = profiles,
    bootText = bootText,
    language = language,
    screenBrightness = screenBrightness,
    screenOffDelay = screenOffDelay,
    displayWindow = displayWindow,
    boltMode = boltMode,
    boardState = boardState,
)
