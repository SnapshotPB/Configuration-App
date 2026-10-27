package com.snapshotpb.configuration.bluetooth.ots

import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import snapshotpb.autococker.v1.AutocockerConfig
import snapshotpb.autococker.v1.AutocockerFireMode
import snapshotpb.common.v1.EyeMode
import snapshotpb.v1.BatteryType
import snapshotpb.v1.BoardModel
import snapshotpb.v1.BoltMode
import snapshotpb.v1.Device
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.DisplayWindow
import snapshotpb.v1.Language
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import snapshotpb.v1.ScreenBrightness

class DecodeDeviceTest {

    @Test
    fun decodesRoundTrippedDevice() {
        val original = Device(
            deviceId = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            lockoutEnabled = true,
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )

        val encoded = ProtoBuf.encodeToByteArray(Device.serializer(), original)
        val decoded = decodeDevice(encoded)

        assertContentEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.lockoutEnabled, decoded.lockoutEnabled)
        assertEquals(original.model, decoded.model)
        assertEquals(original.supportedProfileCount, decoded.supportedProfileCount)
    }

    @Test
    fun decodesDefaultDevice() {
        // An all-defaults Device proto-encodes to zero bytes; decoding must
        // still yield the default instance rather than throwing.
        val encoded = ProtoBuf.encodeToByteArray(Device.serializer(), Device())
        val decoded = decodeDevice(encoded)

        assertEquals(BoardModel.BOARD_MODEL_UNSPECIFIED, decoded.model)
        assertEquals(false, decoded.lockoutEnabled)
        assertTrue(decoded.deviceId.isEmpty())
        assertEquals(0u, decoded.supportedProfileCount)
    }

    @Test
    fun decodesEmptyByteArrayAsDefaults() {
        val decoded = decodeDevice(ByteArray(0))
        assertEquals(BoardModel.BOARD_MODEL_UNSPECIFIED, decoded.model)
        assertEquals(false, decoded.lockoutEnabled)
    }

    @Test
    fun decodesADeviceWithABoardModelThisAppDoesNotKnow() {
        // Field 3 (model) = 42: a board model added to newer firmware. The rest must still decode
        // (M2), where the generated enum would throw and stop the whole sync.
        val encoded = ProtoBuf.encodeToByteArray(
            Device.serializer(),
            Device(lockoutEnabled = true, supportedProfileCount = 2u),
        ) + byteArrayOf(0x18, 42)
        val decoded = decodeDevice(encoded)

        assertEquals(BoardModel.BOARD_MODEL_UNSPECIFIED, decoded.model)
        assertEquals(true, decoded.lockoutEnabled)
        assertEquals(2u, decoded.supportedProfileCount)
    }

    @Test
    fun deviceWireEncodesTheSameBytesAsTheGeneratedDevice() {
        val original = Device(
            deviceId = byteArrayOf(0x01, 0x02),
            lockoutEnabled = true,
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )
        val wire = DeviceWire(
            deviceId = byteArrayOf(0x01, 0x02),
            lockoutEnabled = true,
            model = 1,
            supportedProfileCount = 4u,
        )

        assertContentEquals(
            ProtoBuf.encodeToByteArray(Device.serializer(), original),
            ProtoBuf.encodeToByteArray(DeviceWire.serializer(), wire),
        )
    }

    @Test
    fun profilesWireEncodesTheSameBytesAsTheGeneratedProfiles() {
        // The write path encodes Profiles through the wire copy, so a field that the copy dropped
        // or numbered differently would reach the board wrong.
        val original = Profiles(
            profiles = listOf(
                Profile(
                    name = "P0",
                    fireRateCap = 105u,
                    boardAutoOff = 7u,
                    autococker = AutocockerConfig(
                        fireMode = AutocockerFireMode.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026,
                        eyeMode = EyeMode.EYE_MODE_BREAK_BEAM,
                        eyeThreshold = 1u,
                        eyeDebounce = 2u,
                        searSolenoidDwell = 3u,
                        cockingSolenoidDelay = 4u,
                        cockingSolenoidDwellOpen = 5u,
                        cockingSolenoidDwellClosed = 6u,
                        rampingCustomMultiplier = 7u,
                        rampingCustomTriggerPulls = 8u,
                        rampingCustomRofMinimum = 9u,
                        triggerDebounceOn = 10u,
                        triggerDebounceOff = 11u,
                        cockingSolenoidTimeout = 12u,
                        searSolenoidDelay = 13u,
                    ),
                ),
                Profile(name = "P1"),
            )
        )
        val encoded = ProtoBuf.encodeToByteArray(Profiles.serializer(), original)

        assertContentEquals(encoded, ProtoBuf.encodeToByteArray(ProfilesWire.serializer(), original.toWire()))
        assertEquals(original, decodeProfiles(encoded))
    }

    @Test
    fun decodesProfileFromOlderFirmwareOmittingSearSolenoidDelayAsZero() {
        // A board on older firmware that predates tag 15 omits it on the wire;
        // searSolenoidDelay decodes as 0.
        val legacy = Profiles(
            profiles = listOf(
                Profile(
                    name = "Legacy",
                    autococker = AutocockerConfig(
                        fireMode = AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI,
                        searSolenoidDwell = 45u,
                        cockingSolenoidDelay = 60u,
                    ),
                ),
            ),
        )
        val encoded = ProtoBuf.encodeToByteArray(Profiles.serializer(), legacy)
        val decoded = decodeProfiles(encoded).profiles.single()?.autococker

        assertEquals(0u, decoded?.searSolenoidDelay)
    }

    @Test
    fun decodesProfilesWithAFireModeAndAnEyeModeThisAppDoesNotKnow() {
        // Fire mode 42 and eye mode 9: modes added to newer firmware (M2).
        val wire = ProfilesWire(
            profiles = listOf(
                ProfileWire(
                    name = "New",
                    boardAutoOff = 3u,
                    autococker = AutocockerConfigWire(fireMode = 42, eyeMode = 9, searSolenoidDwell = 45u),
                ),
            )
        )
        val encoded = ProtoBuf.encodeToByteArray(ProfilesWire.serializer(), wire)

        val decoded = decodeProfiles(encoded).profiles.single()
        assertEquals("New", decoded?.name)
        assertEquals(AutocockerFireMode.AUTOCOCKER_FIRE_MODE_UNSPECIFIED, decoded?.autococker?.fireMode)
        assertEquals(EyeMode.EYE_MODE_UNSPECIFIED, decoded?.autococker?.eyeMode)
        assertEquals(45u, decoded?.autococker?.searSolenoidDwell)

        // The wire copy keeps the raw numbers for the next write.
        val raw = decodeProfilesWire(encoded).profiles.single().autococker
        assertEquals(42, raw?.fireMode)
        assertEquals(9, raw?.eyeMode)
    }

    @Test
    fun decodesRoundTrippedDeviceConfig() {
        val original = DeviceConfig(activeProfile = 3u, bootText = "HI")

        val encoded = ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), original)
        val decoded = decodeDeviceConfig(encoded)

        assertEquals(original.activeProfile, decoded.activeProfile)
        assertEquals(original.bootText, decoded.bootText)
    }

    @Test
    fun decodesEveryDeviceConfigField() {
        // decodeDeviceConfig reads through a wire copy of DeviceConfig; this catches a field that
        // the copy dropped or numbered differently.
        val original = DeviceConfig(
            activeProfile = 3u,
            bootText = "HI",
            displayWindow = DisplayWindow(x = 1u, y = 2u, w = 100u, h = 28u),
            language = Language.LANGUAGE_FR,
            screenBrightness = ScreenBrightness.SCREEN_BRIGHTNESS_HIGH,
            screenOffDelay = 45u,
            boltMode = BoltMode.BOLT_MODE_OPEN,
        )

        val encoded = ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), original)

        assertEquals(original, decodeDeviceConfig(encoded))
    }

    @Test
    fun decodesADeviceConfigWithABoltModeThisAppDoesNotKnow() {
        // Field 8 (bolt_mode) = 99: a bolt mode added to newer firmware. The rest must still decode.
        val encoded = ProtoBuf.encodeToByteArray(
            DeviceConfig.serializer(),
            DeviceConfig(activeProfile = 2u, screenOffDelay = 30u),
        ) + byteArrayOf(0x40, 99)
        val decoded = decodeDeviceConfig(encoded)

        assertEquals(2u, decoded.activeProfile)
        assertEquals(30u, decoded.screenOffDelay)
        assertEquals(BoltMode.BOLT_MODE_UNSPECIFIED, decoded.boltMode)
    }

    @Test
    fun decodesADeviceConfigWithALanguageThisAppDoesNotKnow() {
        // Field 4 (language) = 99: a language added to newer firmware. The rest must still decode.
        val encoded = ProtoBuf.encodeToByteArray(
            DeviceConfig.serializer(),
            DeviceConfig(activeProfile = 2u, bootText = "HI"),
        ) + byteArrayOf(0x20, 99)
        val decoded = decodeDeviceConfig(encoded)

        assertEquals(2u, decoded.activeProfile)
        assertEquals("HI", decoded.bootText)
        assertEquals(Language.LANGUAGE_UNSPECIFIED, decoded.language)
    }

    @Test
    fun decodesADeviceConfigWithABrightnessThisAppDoesNotKnow() {
        // Field 5 (screen_brightness) = 99: a level added to newer firmware. The delay on field 6
        // must still decode, so one unknown enum cannot cost the whole message.
        val encoded = ProtoBuf.encodeToByteArray(
            DeviceConfig.serializer(),
            DeviceConfig(activeProfile = 2u, screenOffDelay = 30u),
        ) + byteArrayOf(0x28, 99)
        val decoded = decodeDeviceConfig(encoded)

        assertEquals(2u, decoded.activeProfile)
        assertEquals(30u, decoded.screenOffDelay)
        assertEquals(ScreenBrightness.SCREEN_BRIGHTNESS_UNSPECIFIED, decoded.screenBrightness)
    }

    @Test
    fun decodesADeviceConfigThatCarriesABatteryType() {
        // Field 7 (battery_type): the app has no battery setting, and its wire copy does not list
        // the tag. A board with the field must still give every other value.
        val encoded = ProtoBuf.encodeToByteArray(
            DeviceConfig.serializer(),
            DeviceConfig(
                activeProfile = 2u,
                screenOffDelay = 30u,
                batteryType = BatteryType.BATTERY_TYPE_2S_LIPO,
            ),
        )
        val decoded = decodeDeviceConfig(encoded)

        assertEquals(2u, decoded.activeProfile)
        assertEquals(30u, decoded.screenOffDelay)
        // The value does not come through, so a write cannot send it back by accident.
        assertEquals(BatteryType.BATTERY_TYPE_UNSPECIFIED, decoded.batteryType)
    }

    @Test
    fun decodesEmptyDeviceConfigAsDefaults() {
        val decoded = decodeDeviceConfig(ByteArray(0))
        assertEquals(0u, decoded.activeProfile)
        assertTrue(decoded.bootText.isEmpty())
    }
}
