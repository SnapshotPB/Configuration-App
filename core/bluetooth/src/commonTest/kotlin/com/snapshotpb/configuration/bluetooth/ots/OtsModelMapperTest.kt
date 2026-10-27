package com.snapshotpb.configuration.bluetooth.ots

import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardModel
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import snapshotpb.autococker.v1.AutocockerConfig
import snapshotpb.autococker.v1.AutocockerFireMode
import snapshotpb.common.v1.EyeMode
import snapshotpb.v1.BatteryType
import snapshotpb.v1.BoltMode
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.DisplayWindow
import snapshotpb.v1.Language
import snapshotpb.v1.ScreenBrightness
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class OtsModelMapperTest {

    private val testDeviceUuid = Uuid.parse("12345678-1234-5678-1234-567812345678")
    private val testLocalDate = LocalDate(2026, 5, 23)

    /** A minimal domain Device, for the write-side mappers. */
    private fun domainDevice() = toDomainDevice(
        protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 1u,
        ),
        protoDeviceConfig = DeviceConfig(activeProfile = 1u, bootText = "Hi"),
        protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"))),
        deviceName = "n",
        lastSeenDate = testLocalDate,
    )

    @Test
    fun testDeterministicProfileUuidDerivation() {
        val slot0Uuid = getProfileUuid(testDeviceUuid, 0)
        val slot1Uuid = getProfileUuid(testDeviceUuid, 1)
        val slot2Uuid = getProfileUuid(testDeviceUuid, 2)
        val slot3Uuid = getProfileUuid(testDeviceUuid, 3)

        // Verify uniqueness
        val set = setOf(slot0Uuid, slot1Uuid, slot2Uuid, slot3Uuid)
        assertEquals(4, set.size)

        // Verify stability (multiple calls yield same result)
        assertEquals(slot0Uuid, getProfileUuid(testDeviceUuid, 0))
        assertEquals(slot1Uuid, getProfileUuid(testDeviceUuid, 1))

        // Verify bitwise structure (least significant bits xor slotIndex)
        val testBytes = testDeviceUuid.toByteArray().copyOf()
        testBytes[15] = (testBytes[15].toInt() xor 3).toByte()
        val expectedSlot3Uuid = Uuid.fromByteArray(testBytes)
        assertEquals(expectedSlot3Uuid, slot3Uuid)
    }

    @Test
    fun testEnumMappingFireMode() {
        assertEquals(MarkerFireMode.MECHANICAL, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_MECHANICAL.toDomain())
        assertEquals(MarkerFireMode.SEMI, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI.toDomain())
        assertEquals(MarkerFireMode.TRIGGER_ONLY, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY.toDomain())
        assertEquals(MarkerFireMode.FULL_AUTO, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_FULL_AUTO.toDomain())
        assertEquals(MarkerFireMode.RAMPING_NXL_2026, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026.toDomain())
        assertEquals(MarkerFireMode.CUSTOM_RAMPING, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING.toDomain())
        // Unset reads as unknown rather than as a mode that the board may not run.
        assertEquals(MarkerFireMode.UNKNOWN, AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED.toDomain())

        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_MECHANICAL, MarkerFireMode.MECHANICAL.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI, MarkerFireMode.SEMI.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY, MarkerFireMode.TRIGGER_ONLY.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_FULL_AUTO, MarkerFireMode.FULL_AUTO.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026, MarkerFireMode.RAMPING_NXL_2026.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING, MarkerFireMode.CUSTOM_RAMPING.toProtocol())
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED, MarkerFireMode.UNKNOWN.toProtocol())
    }

    @Test
    fun testEveryFireModeOfTheProtocolHasItsOwnDomainMode() {
        // A new mode of the protocol must not read as a mode that the app already has.
        val known = AutocockerFireMode.entries - AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED
        val domain = known.map { it.toDomain() }

        assertEquals(MarkerFireMode.selectable.toSet(), domain.toSet())
        assertEquals(known, domain.map { it.toProtocol() })
    }

    @Test
    fun testAWriteSendsTheValuesThatTheEditorsHideInTheNxlMode() {
        // The NXL 2026 mode does not read the cap or the ramping values, but the board keeps them
        // for the next mode. A write must send the stored values and not a default.
        val profile = MarkerProfile(
            id = testDeviceUuid,
            name = "League",
            fireMode = MarkerFireMode.RAMPING_NXL_2026,
            fireRateCap = 150,
            rampingCustomMultiplier = 25,
            rampingCustomTriggerPulls = 4,
            rampingCustomROFMinimum = 6,
        )

        val written = profile.toProtocol()
        val arm = assertNotNull(written.autococker)

        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026, arm.fireMode)
        assertEquals(150u, written.fireRateCap)
        assertEquals(25u, arm.rampingCustomMultiplier)
        assertEquals(4u, arm.rampingCustomTriggerPulls)
        assertEquals(6u, arm.rampingCustomRofMinimum)
    }

    @Test
    fun testBoltModeMappingAndHiddenDelayPreservationOnWrite() {
        assertEquals(MarkerBoltMode.CLOSED, BoltMode.BOLT_MODE_CLOSED.toDomain())
        assertEquals(MarkerBoltMode.OPEN, BoltMode.BOLT_MODE_OPEN.toDomain())
        assertNull(BoltMode.BOLT_MODE_UNSPECIFIED.toDomain())
        assertEquals(BoltMode.BOLT_MODE_CLOSED, MarkerBoltMode.CLOSED.toProtocol())
        assertEquals(BoltMode.BOLT_MODE_OPEN, MarkerBoltMode.OPEN.toProtocol())

        // In open-bolt mode, cocking_solenoid_delay is hidden; in closed-bolt or MECHANICAL,
        // sear_solenoid_delay is hidden. Writes must preserve the stored values.
        val semi = MarkerProfile(
            id = testDeviceUuid,
            name = "Semi",
            fireMode = MarkerFireMode.SEMI,
            searSolenoidDelay = 75,
            cockingSolenoidDelay = 90,
        )
        val semiArm = assertNotNull(semi.toProtocol().autococker)
        assertEquals(75u, semiArm.searSolenoidDelay)
        assertEquals(90u, semiArm.cockingSolenoidDelay)

        val mech = semi.copy(fireMode = MarkerFireMode.MECHANICAL)
        val mechArm = assertNotNull(mech.toProtocol().autococker)
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_MECHANICAL, mechArm.fireMode)
        assertEquals(75u, mechArm.searSolenoidDelay)
        assertEquals(90u, mechArm.cockingSolenoidDelay)
    }

    @Test
    fun testEnumMappingEyeMode() {
        assertEquals(MarkerEyeMode.OFF, EyeMode.EYE_MODE_OFF.toDomain())
        assertEquals(MarkerEyeMode.REFLECTIVE, EyeMode.EYE_MODE_REFLECTIVE.toDomain())
        assertEquals(MarkerEyeMode.BREAK_BEAM, EyeMode.EYE_MODE_BREAK_BEAM.toDomain())
        assertEquals(MarkerEyeMode.UNKNOWN, EyeMode.EYE_MODE_UNSPECIFIED.toDomain())

        assertEquals(EyeMode.EYE_MODE_OFF, MarkerEyeMode.OFF.toProtocol())
        assertEquals(EyeMode.EYE_MODE_REFLECTIVE, MarkerEyeMode.REFLECTIVE.toProtocol())
        assertEquals(EyeMode.EYE_MODE_BREAK_BEAM, MarkerEyeMode.BREAK_BEAM.toProtocol())
        assertEquals(EyeMode.EYE_MODE_UNSPECIFIED, MarkerEyeMode.UNKNOWN.toProtocol())
    }

    @Test
    fun testEnumMappingBoardModel() {
        assertEquals(BoardModel.AUTOCOCKER, snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER.toDomain())
        assertEquals(BoardModel.UNKNOWN, snapshotpb.v1.BoardModel.BOARD_MODEL_UNSPECIFIED.toDomain())
    }

    @Test
    fun testProfileMappingRoundTrip() {
        val protoProfile = Profile(
            name = "Test Slot",
            fireRateCap = 105u,
            autococker = AutocockerConfig(
                fireMode = AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026,
                eyeMode = EyeMode.EYE_MODE_BREAK_BEAM,
                eyeThreshold = 22u,
                eyeDebounce = 4u,
                searSolenoidDwell = 6u,
                cockingSolenoidDelay = 12u,
                cockingSolenoidDwellOpen = 25u,
                cockingSolenoidDwellClosed = 18u,
                rampingCustomMultiplier = 2u,
                rampingCustomTriggerPulls = 4u,
                rampingCustomRofMinimum = 5u,
                triggerDebounceOn = 8u,
                triggerDebounceOff = 10u,
                cockingSolenoidTimeout = 33u,
                searSolenoidDelay = 14u,
            )
        )

        val slotIndex = 2
        val domainProfile = protoProfile.toDomain(testDeviceUuid, slotIndex)

        // Verify correct mapping values
        assertEquals(getProfileUuid(testDeviceUuid, slotIndex), domainProfile.id)
        assertEquals("Test Slot", domainProfile.name)
        assertEquals(MarkerFireMode.RAMPING_NXL_2026, domainProfile.fireMode)
        // Tenths of a ball per second: 105 is the 10.5 BPS NXL 2026 ramping limit.
        assertEquals(105, domainProfile.fireRateCap)
        assertEquals(MarkerEyeMode.BREAK_BEAM, domainProfile.eyeMode)
        assertEquals(22, domainProfile.eyeThreshold)
        assertEquals(4, domainProfile.eyeDebounce)
        assertEquals(6, domainProfile.searSolenoidDwell)
        assertEquals(14, domainProfile.searSolenoidDelay)
        assertEquals(12, domainProfile.cockingSolenoidDelay)
        assertEquals(25, domainProfile.cockingSolenoidDwellOpen)
        assertEquals(18, domainProfile.cockingSolenoidDwellClosed)
        assertEquals(2, domainProfile.rampingCustomMultiplier)
        assertEquals(4, domainProfile.rampingCustomTriggerPulls)
        assertEquals(5, domainProfile.rampingCustomROFMinimum) // Check naming translation: rampingCustomRofMinimum -> rampingCustomROFMinimum
        assertEquals(8, domainProfile.triggerDebounceOn)
        assertEquals(10, domainProfile.triggerDebounceOff)
        assertEquals(33, domainProfile.cockingSolenoidTimeout)

        // Convert back — firing mechanics now live under the autococker oneof arm.
        val backToProto = domainProfile.toProtocol()
        assertEquals("Test Slot", backToProto.name)
        assertEquals(105u, backToProto.fireRateCap)
        val backAutococker = assertNotNull(backToProto.autococker)
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026, backAutococker.fireMode)
        assertEquals(EyeMode.EYE_MODE_BREAK_BEAM, backAutococker.eyeMode)
        assertEquals(22u, backAutococker.eyeThreshold)
        assertEquals(4u, backAutococker.eyeDebounce)
        assertEquals(6u, backAutococker.searSolenoidDwell)
        assertEquals(12u, backAutococker.cockingSolenoidDelay)
        assertEquals(25u, backAutococker.cockingSolenoidDwellOpen)
        assertEquals(18u, backAutococker.cockingSolenoidDwellClosed)
        assertEquals(2u, backAutococker.rampingCustomMultiplier)
        assertEquals(4u, backAutococker.rampingCustomTriggerPulls)
        assertEquals(5u, backAutococker.rampingCustomRofMinimum)
        assertEquals(8u, backAutococker.triggerDebounceOn)
        assertEquals(10u, backAutococker.triggerDebounceOff)
        assertEquals(33u, backAutococker.cockingSolenoidTimeout)
        assertEquals(14u, backAutococker.searSolenoidDelay)
    }

    @Test
    fun testToProtocolFloorsNegativeFieldsToZero() {
        // Negatives from legacy/import/watch-sync data must floor to 0, not wrap to a huge UInt.
        val profile = MarkerProfile(
            id = getProfileUuid(testDeviceUuid, 0),
            name = "N",
            fireRateCap = -5,
            eyeThreshold = -1,
            eyeDebounce = -100,
            searSolenoidDelay = -20,
            cockingSolenoidTimeout = -1,
        )
        val proto = profile.toProtocol()
        assertEquals(0u, proto.fireRateCap)
        val autococker = assertNotNull(proto.autococker)
        assertEquals(0u, autococker.eyeThreshold)
        assertEquals(0u, autococker.eyeDebounce)
        assertEquals(0u, autococker.searSolenoidDelay)
        assertEquals(0u, autococker.cockingSolenoidTimeout)
    }

    @Test
    fun testToProtocolTruncatesProfileNameToTenBytes() {
        // The wire buffer is 10 payload bytes; an 11-char ASCII name truncates to 10.
        val profile = MarkerProfile(id = getProfileUuid(testDeviceUuid, 0), name = "abcdefghijk")
        assertEquals("abcdefghij", profile.toProtocol().name)
    }

    @Test
    fun testDeviceConfigAndProfilesMappingRoundTrip() {
        // Read-only Device state.
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            lockoutEnabled = true,
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u
        )
        // Writable DeviceConfig: active_profile is a 1-based index (2 -> slot index 1).
        val protoDeviceConfig = DeviceConfig(
            activeProfile = 2u,
            bootText = "Booting!"
        )

        val profile0 = Profile(name = "Profile 0")
        val profile1 = Profile(name = "Profile 1")
        val profile2 = Profile(name = "Profile 2")
        val profile3 = Profile(name = "Profile 3")
        val protoProfiles = Profiles(
            profiles = listOf(profile0, profile1, profile2, profile3)
        )

        val domainDevice = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = protoDeviceConfig,
            protoProfiles = protoProfiles,
            deviceName = "Snapshot Test Device",
            lastSeenDate = testLocalDate
        )

        assertEquals(testDeviceUuid, domainDevice.id)
        assertEquals("Snapshot Test Device", domainDevice.name)
        assertEquals(testLocalDate, domainDevice.lastSeen)
        assertEquals("Booting!", domainDevice.bootText)
        assertEquals(true, domainDevice.lockoutEnabled)
        assertEquals(BoardModel.AUTOCOCKER, domainDevice.boardModel)
        assertEquals(4, domainDevice.supportedProfileCount)
        assertEquals(getProfileUuid(testDeviceUuid, 1), domainDevice.activeProfileId)
        assertEquals(4, domainDevice.knownProfiles.size)
        assertEquals("Profile 0", domainDevice.knownProfiles[0].name)
        assertEquals("Profile 1", domainDevice.knownProfiles[1].name)

        // Convert back — writes go through DeviceConfig + Profiles, never the read-only Device.
        val backConfig = toProtocolDeviceConfig(domainDevice)
        assertEquals(2u, backConfig.activeProfile)
        assertEquals("Booting!", backConfig.bootText)

        val backProfiles = toProtocolProfiles(domainDevice)
        assertEquals(4, backProfiles.profiles.size)
        assertEquals("Profile 0", backProfiles.profiles[0]?.name)
        assertEquals("Profile 1", backProfiles.profiles[1]?.name)
    }

    @Test
    fun testUnsetActiveProfileAndEmptyDeviceConfig() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 2u
        )
        val protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"), Profile(name = "P1")))

        // active_profile = 0 means unset -> no active profile resolved; empty boot_text falls
        // back to the SnapshotPB default.
        val withUnset = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 0u, bootText = ""),
            protoProfiles = protoProfiles,
            deviceName = "n",
            lastSeenDate = testLocalDate
        )
        assertNull(withUnset.activeProfileId)
        assertEquals("SnapshotPB", withUnset.bootText)

        // No active profile -> active_profile encodes as 0.
        assertEquals(0u, toProtocolDeviceConfig(withUnset).activeProfile)
    }

    @Test
    fun testOutOfRangeActiveProfileResolvesToNull() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 2u,
        )
        val protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"), Profile(name = "P1")))

        // active_profile = 3 is a valid-looking 1-based index but exceeds the 2 known profiles,
        // so it must not resolve to a bogus slot UUID.
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 3u, bootText = "Hi"),
            protoProfiles = protoProfiles,
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertNull(device.activeProfileId)
    }

    @Test
    fun testBootTextNulPaddingIsTrimmedAtFirstNul() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 1u,
        )
        val device = toDomainDevice(
            protoDevice = protoDevice,
            // A fixed-width firmware buffer NUL-pads the trailing bytes.
            protoDeviceConfig = DeviceConfig(activeProfile = 0u, bootText = "Boot\u0000\u0000\u0000"),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertEquals("Boot", device.bootText)
    }

    @Test
    fun testLanguageMapsBothWays() {
        Language.entries.filter { it != Language.LANGUAGE_UNSPECIFIED }.forEach { language ->
            assertEquals(language, assertNotNull(language.toDomain()).toProtocol())
        }
        assertEquals(Language.entries.size - 1, BoardLanguage.entries.size)
        // Firmware from before the field existed reports UNSPECIFIED: the board has no language.
        assertNull(Language.LANGUAGE_UNSPECIFIED.toDomain())
    }

    @Test
    fun testDeviceConfigLanguageAndDisplayWindowRoundTrip() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 1u,
        )
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(
                activeProfile = 1u,
                bootText = "Hi",
                displayWindow = DisplayWindow(x = 0u, y = 3u, w = 128u, h = 26u),
                language = Language.LANGUAGE_DE,
            ),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertEquals(BoardLanguage.DE, device.language)

        assertEquals(
            com.snapshotpb.configuration.model.DisplayWindow(x = 0, y = 3, w = 128, h = 26),
            device.displayWindow,
        )

        val written = toProtocolDeviceConfig(device)
        assertEquals(Language.LANGUAGE_DE, written.language)
        assertEquals(DisplayWindow(x = 0u, y = 3u, w = 128u, h = 26u), written.displayWindow)

        // An absent rectangle leaves the board's own window alone.
        assertNull(toProtocolDeviceConfig(device.copy(displayWindow = null)).displayWindow)

        // An unknown language writes UNSPECIFIED, which leaves the board's language alone.
        assertEquals(
            Language.LANGUAGE_UNSPECIFIED,
            toProtocolDeviceConfig(device.copy(language = null)).language,
        )
    }

    @Test
    fun testDisplayWindowOutOfBoundsDecodesAsNoWindow() {
        // The board enforces the bounds on every write, so this guards a malformed read. `null`
        // reads as "the board reported no window", which no divergence check can fail.
        assertNull(DisplayWindow(x = 0u, y = 0u, w = 200u, h = 26u).toDomain())
        assertNull(DisplayWindow(x = 0u, y = 0u, w = 70u, h = 4u).toDomain())
        assertNull(DisplayWindow(x = 100u, y = 0u, w = 70u, h = 26u).toDomain())
    }

    @Test
    fun testDisplayWindowIsClampedBeforeItIsWritten() {
        // The board REJECTS a whole message that carries an out-of-bounds rectangle, so an illegal
        // local value must never reach the wire and drop the other settings with it.
        val device = toDomainDevice(
            protoDevice = snapshotpb.v1.Device(
                deviceId = testDeviceUuid.toByteArray(),
                model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
                supportedProfileCount = 1u,
            ),
            protoDeviceConfig = DeviceConfig(activeProfile = 1u, bootText = "Hi"),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        ).copy(
            displayWindow = com.snapshotpb.configuration.model.DisplayWindow(
                x = 120,
                y = 30,
                w = 200,
                h = 2,
            )
        )

        assertEquals(
            DisplayWindow(x = 0u, y = 6u, w = 128u, h = 26u),
            toProtocolDeviceConfig(device).displayWindow,
        )
    }

    @Test
    fun testScreenBrightnessMapsBothWays() {
        ScreenBrightness.entries
            .filter { it != ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED }
            .forEach { brightness ->
                assertEquals(brightness, assertNotNull(brightness.toDomain()).toProtocol())
            }
        assertEquals(ScreenBrightness.entries.size - 1, BoardScreenBrightness.entries.size)
        // Firmware from before the field existed reports UNSPECIFIED: the board has no brightness.
        assertNull(ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED.toDomain())
    }

    @Test
    fun testScreenSettingsRoundTripThroughDeviceConfig() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 1u,
        )
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(
                activeProfile = 1u,
                bootText = "Hi",
                screenBrightness = ScreenBrightness.SCREEN_BRIGHTNESS_LOW,
                screenOffDelay = 30u,
                boltMode = BoltMode.BOLT_MODE_OPEN,
            ),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertEquals(BoardScreenBrightness.LOW, device.screenBrightness)
        assertEquals(30, device.screenOffDelay)
        assertEquals(MarkerBoltMode.OPEN, device.boltMode)

        val written = toProtocolDeviceConfig(device)
        assertEquals(ScreenBrightness.SCREEN_BRIGHTNESS_LOW, written.screenBrightness)
        assertEquals(30u, written.screenOffDelay)
        assertEquals(BoltMode.BOLT_MODE_OPEN, written.boltMode)
    }

    @Test
    fun testUnreportedScreenSettingsSendTheUnsetValues() {
        // The unset values leave the board's own settings alone.
        val device = domainDevice().copy(screenBrightness = null, screenOffDelay = null, boltMode = null)
        val written = toProtocolDeviceConfig(device)

        assertEquals(ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED, written.screenBrightness)
        assertEquals(0u, written.screenOffDelay)
        assertEquals(BoltMode.BOLT_MODE_UNSPECIFIED, written.boltMode)
    }

    @Test
    fun testADeviceConfigWriteLeavesTheBatteryTypeUnset() {
        // The app has no battery setting. The unset value leaves the board's own type alone. A
        // concrete value here could give a 2S LiPo board the thresholds of an alkaline cell.
        assertEquals(BatteryType.BATTERY_TYPE_UNSPECIFIED, toProtocolDeviceConfig(domainDevice()).batteryType)
    }

    @Test
    fun testScreenOffDelayIsClampedToTheFirmwareRange() {
        // The board clamps 1000 or more to 999 and still applies the message, so the app clamps
        // first. Otherwise the cached baseline would never match what the board stored.
        val device = domainDevice()
        assertEquals(999u, toProtocolDeviceConfig(device.copy(screenOffDelay = 1500)).screenOffDelay)
        assertEquals(999, device.copy(screenOffDelay = 1500).clampedToFirmwareRanges().screenOffDelay)
        assertNull(device.copy(screenOffDelay = null).clampedToFirmwareRanges().screenOffDelay)

        // A delay below the minimum is no legal value, so it sends 0 and the board keeps its own.
        assertEquals(0u, toProtocolDeviceConfig(device.copy(screenOffDelay = 0)).screenOffDelay)
        assertNull(device.copy(screenOffDelay = 0).clampedToFirmwareRanges().screenOffDelay)
    }

    @Test
    fun testSupportedProfileCountZeroFallsBackToProfilesSize() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 0u, // board did not report a count
        )
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 0u, bootText = ""),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"), Profile(name = "P1"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertEquals(2, device.supportedProfileCount)
    }

    @Test
    fun testProfileWithoutAutocockerArmFallsBackToConfigDefaults() {
        // A profile whose board_config oneof has no autococker arm decodes with the protocol
        // defaults (UNSPECIFIED fire/eye mode -> UNKNOWN, timings 0) instead of crashing.
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 1u,
        )
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 1u, bootText = "Hi"),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "NoArm"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        val profile = device.knownProfiles.single()
        assertEquals(MarkerFireMode.UNKNOWN, profile.fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, profile.eyeMode)
        assertEquals(0, profile.eyeThreshold)
        assertEquals(0, profile.searSolenoidDwell)
        assertEquals(0, profile.searSolenoidDelay)
    }

    @Test
    fun testTakeUtf8BytesTruncatesByByteLength() {
        // Within budget: returned unchanged.
        assertEquals("Booting!", "Booting!".takeUtf8Bytes(10))
        assertEquals("ExactlyTen", "ExactlyTen".takeUtf8Bytes(10)) // 10 ASCII bytes

        // ASCII over budget: truncated to the byte limit (== char count for ASCII).
        assertEquals("0123456789", "0123456789abcdef".takeUtf8Bytes(10))
    }

    @Test
    fun testTakeUtf8BytesDoesNotSplitMultibyteCodePoints() {
        // "é" is 2 UTF-8 bytes. Five of them = 10 bytes, exactly the limit.
        assertEquals("ééééé", "ééééé".takeUtf8Bytes(10))

        // A 2-byte char that would straddle the limit is dropped whole, never split.
        // "abcdefghi" = 9 bytes; adding "é" (2 bytes) would be 11 > 10, so it is excluded.
        assertEquals("abcdefghi", "abcdefghié".takeUtf8Bytes(10))

        // A 4-byte supplementary code point (emoji) is kept or dropped as a unit.
        // "abcdef" = 6 bytes; "😀" is 4 bytes -> 10 total, kept intact.
        assertEquals("abcdef😀", "abcdef😀".takeUtf8Bytes(10))
        // "abcdefg" = 7 bytes; the 4-byte emoji would overflow (11), so it is dropped.
        assertEquals("abcdefg", "abcdefg😀".takeUtf8Bytes(10))
    }

    // --- Slot semantics: the domain list index IS the board slot, in both directions ---

    @Test
    fun testProfileSlotsRoundTripThroughReadAndWrite() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 3u,
        )
        val protoProfiles = Profiles(
            profiles = listOf(Profile(name = "P0"), Profile(name = "P1"), Profile(name = "P2"))
        )

        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 2u, bootText = "Hi"),
            protoProfiles = protoProfiles,
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )

        // Slot 1 (1-based index 2) is active, and its id derives from that slot.
        assertEquals(getProfileUuid(testDeviceUuid, 1), device.activeProfileId)

        // Writing back must place each profile at the slot it came from and re-encode the same
        // 1-based active index.
        val backProfiles = toProtocolProfiles(device, protoProfiles)
        assertEquals(listOf("P0", "P1", "P2"), backProfiles.profiles.map { it?.name })
        assertEquals(2u, toProtocolDeviceConfig(device).activeProfile)
    }

    @Test
    fun testNullSlotTruncatesRatherThanShiftingLaterProfilesDown() {
        // A null slot silently dropped mid-list used to compact the domain list, so every later
        // profile moved down one slot on the next write — changing which profile the board treats
        // as active and mis-pairing the carried-forward board-owned fields. Take the dense leading
        // run instead, so read and write agree by construction.
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 3u,
        )
        val protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"), null, Profile(name = "P2")))

        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 1u, bootText = "Hi"),
            protoProfiles = protoProfiles,
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )

        assertEquals(listOf("P0"), device.knownProfiles.map { it.name })
        assertEquals(getProfileUuid(testDeviceUuid, 0), device.activeProfileId)

        // And the write puts P0 back at slot 0 — it never becomes "the profile after the gap".
        assertEquals(listOf("P0"), toProtocolProfiles(device, protoProfiles).profiles.map { it?.name })
    }

    @Test
    fun testActiveProfilePastTheDenseSlotPrefixDoesNotResolve() {
        val protoDevice = snapshotpb.v1.Device(
            deviceId = testDeviceUuid.toByteArray(),
            model = snapshotpb.v1.BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 3u,
        )
        // active_profile = 3 points at the slot beyond the gap, which the domain list does not carry.
        val device = toDomainDevice(
            protoDevice = protoDevice,
            protoDeviceConfig = DeviceConfig(activeProfile = 3u, bootText = "Hi"),
            protoProfiles = Profiles(profiles = listOf(Profile(name = "P0"), null, Profile(name = "P2"))),
            deviceName = "n",
            lastSeenDate = testLocalDate,
        )
        assertNull(device.activeProfileId)
    }

    @Test
    fun testProfileNameByteCapMatchesTheFirmwareBuffer() {
        // nanopb `char[11]` reserves one byte for the NUL, so the wire payload is at most 10 bytes.
        // An 11-byte name overflows and the WHOLE Profiles message fails to decode on the board.
        val profile = MarkerProfile(id = getProfileUuid(testDeviceUuid, 0), name = "0123456789a")
        assertEquals("0123456789", profile.toProtocol().name)
        assertEquals(10, profile.toProtocol().name.encodeToByteArray().size)
    }

    @Test
    fun testTenCharacterProfileNameRoundTrips() {
        // A 10-character name fills the wire cap. The mapper keeps all 10 characters in both
        // directions.
        val profile = MarkerProfile(id = getProfileUuid(testDeviceUuid, 0), name = "SnapshotPB")
        val proto = profile.toProtocol()
        assertEquals("SnapshotPB", proto.name)
        assertEquals("SnapshotPB", proto.toDomain(testDeviceUuid, 0).name)
    }
}
