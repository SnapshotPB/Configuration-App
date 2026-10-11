package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class BoardIdentityTest {

    private val oldId = "AC:EB:E6:81:A8:5C".toDeviceUuid()
    private val newId = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
    private val addedProfileId = Uuid.parse("12345678-1234-4234-8234-123456789abc")

    private fun oldDevice(): Device {
        val profiles = listOf(
            MarkerProfile(id = getProfileUuid(oldId, 0), name = "Semi"),
            MarkerProfile(id = getProfileUuid(oldId, 1), name = "Ramp"),
            // A profile that the user added and did not save: its id is not a slot id.
            MarkerProfile(id = addedProfileId, name = "New"),
        )
        return Device(
            id = oldId,
            name = "My marker",
            lastSeen = LocalDate(2026, 9, 1),
            lastModified = 42L,
            knownProfiles = profiles,
            photoPath = "file:///data/device_photo_${oldId}_7",
            bootText = "HELLO",
            activeProfileId = profiles[1].id,
            boardState = BoardState(
                bootText = "HELLO",
                activeProfileId = profiles[1].id,
                lockoutEnabled = false,
                knownProfiles = profiles.take(2),
            ),
        )
    }

    @Test
    fun movedToBoardId_movesTheIdAndTheSlotProfileIdsAndKeepsEveryOtherField() {
        val old = oldDevice()

        val moved = old.movedToBoardId(newId)

        assertEquals(newId, moved.id)
        assertEquals(
            listOf(getProfileUuid(newId, 0), getProfileUuid(newId, 1), addedProfileId),
            moved.knownProfiles.map { it.id },
        )
        assertEquals(getProfileUuid(newId, 1), moved.activeProfileId)
        assertEquals(getProfileUuid(newId, 1), moved.boardState?.activeProfileId)
        assertEquals(
            listOf(getProfileUuid(newId, 0), getProfileUuid(newId, 1)),
            moved.boardState?.knownProfiles?.map { it.id },
        )
        // Only the ids change: the name, the photo, the profiles and the baseline stay.
        assertEquals(old.name, moved.name)
        assertEquals(old.photoPath, moved.photoPath)
        assertEquals(old.lastModified, moved.lastModified)
        assertEquals(old.knownProfiles.map { it.name }, moved.knownProfiles.map { it.name })
        assertEquals(old.bootText, moved.boardState?.bootText)
    }

    @Test
    fun recordBoardId_keepsOneScanKeyForEachBoard() {
        val stateManager = BluetoothStateManager()

        stateManager.recordBoardId("old-address", newId.toString())
        stateManager.recordBoardId("other-board", oldId.toString())
        // The board changed its address during the session: the old key must not find it.
        stateManager.recordBoardId("new-address", newId.toString())

        assertEquals(
            mapOf("other-board" to oldId.toString(), "new-address" to newId.toString()),
            stateManager.boardIds.value,
        )
    }

    @Test
    fun resolveBoardId_givesTheBoardIdOfAReadScanKey_andElseTheIdItself() {
        val boardIds = mapOf("scan-a" to newId.toString())

        assertEquals(newId.toString(), resolveBoardId(boardIds, "scan-a"))
        assertEquals("scan-b", resolveBoardId(boardIds, "scan-b"))
        assertEquals("scan-a", scanKeyOfBoard(boardIds, newId.toString()))
        assertNull(scanKeyOfBoard(boardIds, oldId.toString()))
    }
}
