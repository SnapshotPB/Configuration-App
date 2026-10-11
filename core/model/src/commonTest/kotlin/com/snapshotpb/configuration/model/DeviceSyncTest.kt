package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Cover for the Wear sync rules: the synced field list, the peer merge, and the add and delete checks. */
class DeviceSyncTest {

    private val deviceId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val otherId = Uuid.parse("99999999-2222-3333-4444-555555555555")
    private val profileId = Uuid.parse("11111111-2222-3333-4444-555555555556")

    private fun device(
        id: Uuid = deviceId,
        name: String = "Marker",
        lastModified: Long = 100L,
        photoPath: String = "",
        boardState: BoardState? = null,
        language: BoardLanguage? = null,
        displayWindow: DisplayWindow? = null,
        profiles: List<MarkerProfile> = listOf(MarkerProfile(id = profileId, name = "P0")),
        lastSeen: LocalDate = LocalDate(2026, 9, 1),
    ) = Device(
        id = id,
        name = name,
        lastSeen = lastSeen,
        lastModified = lastModified,
        knownProfiles = profiles,
        photoPath = photoPath,
        language = language,
        displayWindow = displayWindow,
        boardState = boardState,
    )

    private fun baseline(bootText: String = "SnapshotPB") = BoardState(
        bootText = bootText,
        activeProfileId = null,
        lockoutEnabled = false,
        knownProfiles = listOf(MarkerProfile(id = profileId, name = "P0")),
    )

    // --- syncedConfigEquals ---

    @Test
    fun syncedConfigEquals_seesABoardStateOnlyChange() {
        assertFalse(device(boardState = baseline("A")).syncedConfigEquals(device(boardState = baseline("B"))))
    }

    @Test
    fun syncedConfigEquals_seesASourceSlotOnlyChange() {
        // The watch writes to the board too, so it must get the source slot of each profile.
        val withSlot = device(profiles = listOf(MarkerProfile(id = profileId, name = "P0", sourceSlot = 1)))

        assertFalse(device().syncedConfigEquals(withSlot))
    }

    @Test
    fun syncedConfigEquals_seesADisplayWindowOnlyChange() {
        assertFalse(
            device(displayWindow = DisplayWindow.FULL_PANEL)
                .syncedConfigEquals(device(displayWindow = DisplayWindow(x = 4, y = 2, w = 100, h = 28))),
        )
    }

    @Test
    fun syncedConfigEquals_ignoresLastModifiedAndLastSeen() {
        assertTrue(
            device(lastModified = 1L, lastSeen = LocalDate(2026, 1, 1))
                .syncedConfigEquals(device(lastModified = 2L, lastSeen = LocalDate(2026, 2, 2))),
        )
    }

    // --- mergeFromPeer ---

    @Test
    fun mergeFromPeer_takesTheBoardStateAndTheDisplayWindowOfThePeer() {
        val local = device(boardState = baseline("Old"), displayWindow = DisplayWindow.FULL_PANEL)
        val window = DisplayWindow(x = 4, y = 2, w = 100, h = 28)
        val merged = local.mergeFromPeer(device(name = "Peer", boardState = baseline("New"), displayWindow = window))

        assertEquals("Peer", merged.name)
        assertEquals("New", merged.boardState?.bootText)
        assertEquals(window, merged.displayWindow)
    }

    @Test
    fun mergeFromPeer_keepsLocalValuesThatThePeerDoesNotHave() {
        val local = device(
            photoPath = "file:///local.png",
            boardState = baseline("Kept"),
            language = BoardLanguage.DE,
            displayWindow = DisplayWindow.FULL_PANEL,
            lastSeen = LocalDate(2026, 9, 20),
        )
        val merged = local.mergeFromPeer(device(lastSeen = LocalDate(2026, 9, 2)))

        assertEquals("file:///local.png", merged.photoPath)
        assertEquals("Kept", merged.boardState?.bootText)
        assertEquals(BoardLanguage.DE, merged.language)
        assertEquals(DisplayWindow.FULL_PANEL, merged.displayWindow)
        assertEquals(LocalDate(2026, 9, 20), merged.lastSeen)
    }

    // --- applyPeerDevice ---

    @Test
    fun applyPeerDevice_mergesANewerCopyAndTakesItsTimestamp() {
        val current = listOf(device(lastModified = 100L, boardState = baseline("Old")), device(id = otherId))
        val result = applyPeerDevice(
            current = current,
            incoming = device(name = "Peer", boardState = baseline("New")),
            incomingLastModified = 200L,
            peerSchemaVersion = DEVICE_SYNC_SCHEMA_VERSION,
        )

        assertEquals(PeerSyncOutcome.UPDATED, result.outcome)
        val merged = result.devices.single { it.id == deviceId }
        assertEquals("Peer", merged.name)
        assertEquals("New", merged.boardState?.bootText)
        assertEquals(200L, merged.lastModified)
        assertEquals(2, result.devices.size)
    }

    @Test
    fun applyPeerDevice_ignoresACopyThatIsNotNewer() {
        val current = listOf(device(lastModified = 200L))
        val result = applyPeerDevice(current, device(name = "Echo"), 200L, DEVICE_SYNC_SCHEMA_VERSION)

        assertEquals(PeerSyncOutcome.IGNORED_NOT_NEWER, result.outcome)
        assertSame(current, result.devices)
    }

    @Test
    fun applyPeerDevice_rejectsAPeerFromBeforeTheSourceSlots() {
        // Older peers do not know MarkerProfile.sourceSlot. They send each source slot back as null.
        val current = listOf(device(lastModified = 100L))
        val result = applyPeerDevice(current, device(name = "Old build"), 900L, peerSchemaVersion = 2)

        assertEquals(PeerSyncOutcome.IGNORED_OLD_SCHEMA, result.outcome)
        assertSame(current, result.devices)
    }

    @Test
    fun applyPeerDevice_rejectsAPeerFromBeforeTheCustomRampingMode() {
        // Version 3 peers do not know MarkerFireMode.CUSTOM_RAMPING. They decode it as the default
        // (SEMI), so an edit from such a peer could replace the custom ramping mode.
        val current = listOf(device(lastModified = 100L))
        val result = applyPeerDevice(current, device(name = "Old build"), 900L, peerSchemaVersion = 3)

        assertEquals(PeerSyncOutcome.IGNORED_OLD_SCHEMA, result.outcome)
        assertSame(current, result.devices)
    }

    @Test
    fun applyPeerDevice_rejectsAnOlderSchema() {
        val current = listOf(device(lastModified = 100L))
        val result = applyPeerDevice(current, device(name = "Old build"), 900L, peerSchemaVersion = 0)

        assertEquals(PeerSyncOutcome.IGNORED_OLD_SCHEMA, result.outcome)
        assertSame(current, result.devices)
    }

    @Test
    fun applyPeerDevice_rejectsAPeerFromBeforeTheUnknownModes() {
        // Version 1 peers do not know MarkerFireMode.UNKNOWN or MarkerEyeMode.UNKNOWN. They decode
        // them as the defaults, so an edit from such a peer could change an unknown board mode.
        val current = listOf(device(lastModified = 100L))
        val result = applyPeerDevice(current, device(name = "Old build"), 900L, peerSchemaVersion = 1)

        assertEquals(PeerSyncOutcome.IGNORED_OLD_SCHEMA, result.outcome)
        assertSame(current, result.devices)
    }

    @Test
    fun applyPeerDevice_addsAnUnknownDeviceOnlyOnce() {
        val first = applyPeerDevice(emptyList(), device(), 100L, DEVICE_SYNC_SCHEMA_VERSION)
        assertEquals(PeerSyncOutcome.ADDED, first.outcome)
        assertEquals(1, first.devices.size)

        // The same event again (a second delivery) finds the device and changes nothing.
        val second = applyPeerDevice(first.devices, device(), 100L, DEVICE_SYNC_SCHEMA_VERSION)
        assertEquals(PeerSyncOutcome.IGNORED_NOT_NEWER, second.outcome)
        assertEquals(1, second.devices.size)
    }

    @Test
    fun applyPeerDevice_doesNotBringBackADeviceDeletedAfterThePeerCopy() {
        val result = applyPeerDevice(
            current = emptyList(),
            incoming = device(),
            incomingLastModified = 100L,
            peerSchemaVersion = DEVICE_SYNC_SCHEMA_VERSION,
            localDeleteMarkerAt = 150L,
        )

        assertEquals(PeerSyncOutcome.IGNORED_DELETED, result.outcome)
        assertTrue(result.devices.isEmpty())
    }

    @Test
    fun applyPeerDevice_addsADeviceEditedAfterTheDelete() {
        val result = applyPeerDevice(
            current = emptyList(),
            incoming = device(),
            incomingLastModified = 200L,
            peerSchemaVersion = DEVICE_SYNC_SCHEMA_VERSION,
            localDeleteMarkerAt = 150L,
        )

        assertEquals(PeerSyncOutcome.ADDED, result.outcome)
        assertEquals(200L, result.devices.single().lastModified)
    }

    @Test
    fun applyPeerDevice_usesTheSavedPhotoPathOrKeepsTheLocalOne() {
        val current = listOf(device(lastModified = 100L, photoPath = "file:///old.png"))

        val kept = applyPeerDevice(current, device(), 200L, DEVICE_SYNC_SCHEMA_VERSION)
        assertEquals("file:///old.png", kept.devices.single().photoPath)

        val saved = applyPeerDevice(
            current, device(), 200L, DEVICE_SYNC_SCHEMA_VERSION, localPhotoPath = "file:///new.png",
        )
        assertEquals("file:///new.png", saved.devices.single().photoPath)
    }
}
