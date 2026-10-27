package com.snapshotpb.configuration.bluetooth.ots

import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import kotlinx.datetime.LocalDate
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.DeviceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * The write side of the Profiles mapping: which board slot each profile takes its board-owned
 * fields from (L17, #119), and how an enum number this app does not know goes back to the board (M2).
 */
class ProfilesWriteMappingTest {

    private val deviceUuid = Uuid.parse("12345678-1234-5678-1234-567812345678")

    private fun arm(
        fireMode: Int = 2,
        eyeMode: Int = 1,
        searSolenoidDwell: UInt = 45u,
        cockingSolenoidDelay: UInt = 60u,
    ) = AutocockerConfigWire(
        fireMode = fireMode,
        eyeMode = eyeMode,
        searSolenoidDwell = searSolenoidDwell,
        cockingSolenoidDelay = cockingSolenoidDelay,
    )

    /** Three board slots with distinct names, distinct settings and distinct board_auto_off. */
    private val board = ProfilesWire(
        profiles = listOf(
            ProfileWire(name = "Alpha", boardAutoOff = 10u, autococker = arm(fireMode = 1, searSolenoidDwell = 40u)),
            ProfileWire(name = "Bravo", boardAutoOff = 20u, autococker = arm(fireMode = 2, eyeMode = 2, searSolenoidDwell = 50u)),
            ProfileWire(name = "Charlie", boardAutoOff = 30u, autococker = arm(fireMode = 4, eyeMode = 3, cockingSolenoidDelay = 70u)),
        )
    )

    private fun deviceFrom(profiles: ProfilesWire): Device = toDomainDevice(
        protoDevice = snapshotpb.v1.Device(deviceId = deviceUuid.toByteArray(), supportedProfileCount = 4u),
        protoDeviceConfig = DeviceConfig(activeProfile = 1u, bootText = "Hi"),
        protoProfiles = profiles.toGenerated(),
        deviceName = "n",
        lastSeenDate = LocalDate(2026, 9, 22),
    )

    /** Deletes [slot] the way the Profiles screen does: later profiles move down and take the slot id. */
    private fun Device.withSlotDeleted(slot: Int): Device {
        val kept = knownProfiles.filterIndexed { index, _ -> index != slot }
        return copy(knownProfiles = kept.mapIndexed { index, profile -> profile.copy(id = getProfileUuid(deviceUuid, index)) })
    }

    @Test
    fun withoutADelete_eachProfileKeepsItsOwnSlot() {
        val written = toProtocolProfilesWire(deviceFrom(board), board)

        assertEquals(listOf(10u, 20u, 30u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun afterAMiddleDelete_boardAutoOffFollowsTheProfile() {
        val device = deviceFrom(board).withSlotDeleted(1)

        val written = toProtocolProfilesWire(device, board)

        // Charlie moved to slot 1, but it keeps its own board_auto_off, not Bravo's (L17).
        assertEquals(listOf("Alpha", "Charlie"), written.profiles.map { it.name })
        assertEquals(listOf(10u, 30u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun afterAFirstSlotDelete_boardAutoOffFollowsTheProfile() {
        val written = toProtocolProfilesWire(deviceFrom(board).withSlotDeleted(0), board)

        assertEquals(listOf(20u, 30u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun afterADelete_anEditedProfileStillPairsWithItsSourceSlot() {
        val deleted = deviceFrom(board).withSlotDeleted(1)
        val edited = deleted.copy(
            knownProfiles = listOf(
                deleted.knownProfiles[0],
                deleted.knownProfiles[1].copy(name = "Delta", fireRateCap = 99),
            )
        )

        val written = toProtocolProfilesWire(edited, board)

        assertEquals(listOf(10u, 30u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun afterADeleteOfOneOfTwoIdenticalProfiles_theEarlierSlotsPairByIndex() {
        val twins = ProfilesWire(
            profiles = listOf(
                ProfileWire(name = "Same", boardAutoOff = 1u, autococker = arm()),
                ProfileWire(name = "Same", boardAutoOff = 2u, autococker = arm()),
                ProfileWire(name = "Same", boardAutoOff = 3u, autococker = arm()),
            )
        )

        val written = toProtocolProfilesWire(deviceFrom(twins).withSlotDeleted(2), twins)

        // Identical content cannot tell the slots apart; a tie keeps the index pairing.
        assertEquals(listOf(1u, 2u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun anAddedProfileHasNoBoardSlot() {
        val device = deviceFrom(board)
        val added = device.copy(
            knownProfiles = device.knownProfiles + device.knownProfiles[0].copy(
                id = getProfileUuid(deviceUuid, 3),
                name = "Echo",
            )
        )

        assertEquals(listOf(0, 1, 2, null), boardSlotSources(deviceUuid, added.knownProfiles, board.profiles))
        assertEquals(0u, toProtocolProfilesWire(added, board).profiles[3].boardAutoOff)
    }

    @Test
    fun aReadGivesEachProfileItsSlotAsTheSourceSlot() {
        assertEquals(listOf(0, 1, 2), deviceFrom(board).knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun afterADeleteAndAnAdd_boardAutoOffFollowsTheSourceSlot() {
        // Delete Alpha, then add Delta: the list has three profiles again, as on the board.
        val deleted = deviceFrom(board).withSlotDeleted(0)
        val added = deleted.copy(
            knownProfiles = deleted.knownProfiles +
                MarkerProfile(id = getProfileUuid(deviceUuid, 2), name = "Delta"),
        )

        val written = toProtocolProfilesWire(added, board)

        // A pairing by index gave Bravo the value of Alpha and Charlie the value of Bravo (#119).
        assertEquals(listOf("Bravo", "Charlie", "Delta"), written.profiles.map { it.name })
        assertEquals(listOf(20u, 30u, 0u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun afterADeleteOfTheFirstOfIdenticalProfiles_boardAutoOffFollowsTheSourceSlot() {
        val twins = ProfilesWire(
            profiles = listOf(
                ProfileWire(name = "Same", boardAutoOff = 1u, autococker = arm()),
                ProfileWire(name = "Same", boardAutoOff = 2u, autococker = arm()),
                ProfileWire(name = "Same", boardAutoOff = 3u, autococker = arm()),
            )
        )

        val written = toProtocolProfilesWire(deviceFrom(twins).withSlotDeleted(0), twins)

        // Content cannot tell identical profiles apart, but the source slot can (#119).
        assertEquals(listOf(2u, 3u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun aSourceSlotOutOfRangeOrTakenTwicePairsWithNoSlot() {
        val device = deviceFrom(board)
        val profiles = listOf(
            device.knownProfiles[1],
            device.knownProfiles[0].copy(sourceSlot = 1),
            device.knownProfiles[2].copy(sourceSlot = 7),
        )

        assertEquals(listOf(1, null, null), boardSlotSources(deviceUuid, profiles, board.profiles))
    }

    @Test
    fun profilesWithNoSourceSlot_fallBackToTheContentAlignment() {
        // A copy stored before the app kept source slots.
        val legacy = deviceFrom(board).let { device ->
            device.copy(knownProfiles = device.knownProfiles.map { it.copy(sourceSlot = null) })
        }.withSlotDeleted(1)

        val written = toProtocolProfilesWire(legacy, board)

        assertEquals(listOf(10u, 30u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun contentAlignmentFallback_usesSearSolenoidDelay() {
        val boardWithSearDelay = ProfilesWire(
            profiles = listOf(
                ProfileWire(name = "Same", boardAutoOff = 10u, autococker = arm()),
                ProfileWire(
                    name = "Same",
                    boardAutoOff = 20u,
                    autococker = arm().copy(searSolenoidDelay = 85u),
                ),
            ),
        )
        // Legacy profile without sourceSlot whose only distinguishing setting is
        // searSolenoidDelay must align to slot 1 and inherit boardAutoOff = 20u.
        val legacy = deviceFrom(boardWithSearDelay).let { device ->
            device.copy(
                knownProfiles = listOf(
                    device.knownProfiles[1].copy(
                        id = getProfileUuid(deviceUuid, 0),
                        sourceSlot = null,
                        searSolenoidDelay = 85,
                    ),
                ),
            )
        }

        val written = toProtocolProfilesWire(legacy, boardWithSearDelay)

        assertEquals(listOf(20u), written.profiles.map { it.boardAutoOff })
    }

    @Test
    fun anUnknownFireModeAndEyeModeGoBackUnchanged() {
        val newer = ProfilesWire(
            profiles = listOf(
                ProfileWire(name = "Future", boardAutoOff = 5u, autococker = arm(fireMode = 42, eyeMode = 9)),
            )
        )
        val device = deviceFrom(newer)
        // The unknown numbers read as unknown, so the editors do not show a mode the board may not run.
        assertEquals(MarkerFireMode.UNKNOWN, device.knownProfiles[0].fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, device.knownProfiles[0].eyeMode)

        val written = toProtocolProfilesWire(device.copy(bootText = "Edited"), newer)

        // A save never changes the board's unknown mode to a known one by itself (M2).
        val sent = decodeProfilesWire(ProtoBuf.encodeToByteArray(ProfilesWire.serializer(), written))
        assertEquals(42, sent.profiles[0].autococker?.fireMode)
        assertEquals(9, sent.profiles[0].autococker?.eyeMode)
        assertEquals(5u, sent.profiles[0].boardAutoOff)
    }

    @Test
    fun aModeThatTheUserSelectsReplacesTheUnknownOne() {
        val newer = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Future", autococker = arm(fireMode = 42, eyeMode = 9))),
        )
        val device = deviceFrom(newer)
        val edited = device.copy(
            knownProfiles = listOf(device.knownProfiles[0].copy(fireMode = MarkerFireMode.SEMI)),
        )

        val written = toProtocolProfilesWire(edited, newer).profiles[0].autococker

        assertEquals(2, written?.fireMode) // AUTOCOCKER_FIRE_MODE_SEMI
        assertEquals(9, written?.eyeMode) // the eye mode was not touched
    }

    @Test
    fun theKnownModeThatWasTheOldFallbackReplacesTheUnknownOne() {
        // MECHANICAL and OFF were the display of an unknown mode before. They are real choices now,
        // so a user who selects them gets them, and the unknown number does not go back.
        val newer = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Future", autococker = arm(fireMode = 42, eyeMode = 9))),
        )
        val device = deviceFrom(newer)
        val edited = device.copy(
            knownProfiles = listOf(
                device.knownProfiles[0].copy(fireMode = MarkerFireMode.MECHANICAL, eyeMode = MarkerEyeMode.OFF),
            ),
        )

        val written = toProtocolProfilesWire(edited, newer).profiles[0].autococker

        assertEquals(1, written?.fireMode) // AUTOCOCKER_FIRE_MODE_MECHANICAL
        assertEquals(1, written?.eyeMode) // EYE_MODE_OFF
    }

    @Test
    fun anUnspecifiedModeGoesBackAsUnspecified() {
        // The board sent 0 (UNSPECIFIED). It reads as unknown and goes back as 0, not as a known
        // mode that the board did not report.
        val unset = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Unset", autococker = arm(fireMode = 0, eyeMode = 0))),
        )
        val device = deviceFrom(unset)
        assertEquals(MarkerFireMode.UNKNOWN, device.knownProfiles[0].fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, device.knownProfiles[0].eyeMode)

        val written = toProtocolProfilesWire(device, unset).profiles[0].autococker

        assertEquals(0, written?.fireMode)
        assertEquals(0, written?.eyeMode)
    }

    @Test
    fun anUnknownModeFollowsTheBoardWhenTheBoardHasAKnownMode() {
        // The stored profile still says unknown, but the board now reports SEMI. A save leaves the
        // board's mode alone, as it does for an unknown number.
        val board = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Now", autococker = arm(fireMode = 2, eyeMode = 2))),
        )
        val stale = deviceFrom(board).let { device ->
            device.copy(
                knownProfiles = listOf(
                    device.knownProfiles[0].copy(fireMode = MarkerFireMode.UNKNOWN, eyeMode = MarkerEyeMode.UNKNOWN),
                ),
            )
        }

        val written = toProtocolProfilesWire(stale, board).profiles[0].autococker

        assertEquals(2, written?.fireMode) // AUTOCOCKER_FIRE_MODE_SEMI
        assertEquals(2, written?.eyeMode) // EYE_MODE_REFLECTIVE
    }

    @Test
    fun theReadBackHelpersMapActiveProfileAndBootText() {
        assertEquals(1, DeviceConfig(activeProfile = 2u).activeProfileSlot(profileCount = 2))
        assertNull(DeviceConfig(activeProfile = 3u).activeProfileSlot(profileCount = 2))
        assertNull(DeviceConfig(activeProfile = 0u).activeProfileSlot(profileCount = 2))
        assertEquals("ABC", DeviceConfig(bootText = "ABC\u0000\u0000").bootTextToDomain())
        assertEquals("SnapshotPB", DeviceConfig(bootText = "").bootTextToDomain())
    }

    @Test
    fun theStoredCopyRecordsTheKnownModeThatAnUnknownModeWroteBack() {
        // "Keep my values" on a stale UNKNOWN writes the board's SEMI. The stored copy must say
        // SEMI, or the next read shows the same conflict again.
        val now = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Now", autococker = arm(fireMode = 2, eyeMode = 2))),
        )
        val stale = deviceFrom(now).let { device ->
            device.copy(
                knownProfiles = device.knownProfiles.map {
                    it.copy(fireMode = MarkerFireMode.UNKNOWN, eyeMode = MarkerEyeMode.UNKNOWN)
                },
            )
        }
        val written = toProtocolProfilesWire(stale, now)

        val recorded = stale.knownProfiles.withModesAsWritten(written).single()

        assertEquals(MarkerFireMode.SEMI, recorded.fireMode)
        assertEquals(MarkerEyeMode.REFLECTIVE, recorded.eyeMode)
    }

    @Test
    fun anUnknownNumberThatWentBackStaysUnknownInTheStoredCopy() {
        val newer = ProfilesWire(
            profiles = listOf(ProfileWire(name = "Future", autococker = arm(fireMode = 42, eyeMode = 9))),
        )
        val device = deviceFrom(newer)
        val written = toProtocolProfilesWire(device, newer)

        val recorded = device.knownProfiles.withModesAsWritten(written).single()

        assertEquals(MarkerFireMode.UNKNOWN, recorded.fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, recorded.eyeMode)
    }
}
