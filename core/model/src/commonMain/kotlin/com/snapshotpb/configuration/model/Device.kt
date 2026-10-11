package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Device(
    val id: Uuid,
    val name: String,
    val lastSeen: LocalDate,
    val lastModified: Long = 0L,
    val knownProfiles: List<MarkerProfile>,
    val photoPath: String = "",
    val bootText: String = "SnapshotPB",
    val activeProfileId: Uuid? = null,
    val lockoutEnabled: Boolean = false,
    /** Language of the board's own menu text; `null` until the board reports one. */
    val language: BoardLanguage? = null,
    /** Brightness the board drives its screen at; `null` until the board reports one. */
    val screenBrightness: BoardScreenBrightness? = null,
    /**
     * Seconds the board leaves the screen on after the last button press, 1-999; `null` until the
     * board reports one. The board counts from the last button press, and a shot does not hold the
     * screen on.
     */
    val screenOffDelay: Int? = null,
    /**
     * The part of the panel the marker's grip frame reveals; `null` until the board reports one. A
     * `null` write leaves the board's current window alone, like the three settings above.
     */
    val displayWindow: DisplayWindow? = null,
    /** Board-reported model. Read-only board fact; not part of divergence. */
    val boardModel: BoardModel = BoardModel.AUTOCOCKER,
    /** Number of profile slots the board supports. Read-only board fact; drives slot UI. */
    val supportedProfileCount: Int = DEFAULT_PROFILE_COUNT,
    val boardState: BoardState? = null,
    val advertisementData: AdvertisementData? = null,
) {
    fun hasDiverged(): Boolean {
        val bs = boardState ?: return false
        // lockoutEnabled is a read-only board fact (there is no app-writable lockout field), so it
        // is deliberately NOT part of divergence: a board-side flip must not raise an unresolvable
        // "unsaved" state the user cannot push back. It always tracks the latest board read instead.
        return bootText != bs.bootText ||
            activeProfileId != bs.activeProfileId ||
            !boardValuesMatch(language, bs.language) ||
            !boardValuesMatch(screenBrightness, bs.screenBrightness) ||
            !boardValuesMatch(screenOffDelay, bs.screenOffDelay) ||
            displayWindowDiverged(displayWindow, bs.displayWindow) ||
            hasProfilesDiverged()
    }

    fun hasProfilesDiverged(): Boolean {
        val bs = boardState ?: return false
        if (knownProfiles.size != bs.knownProfiles.size) return true
        for (profile in knownProfiles) {
            val baseProfile = bs.knownProfiles.firstOrNull { it.id == profile.id } ?: return true
            // The source slot is not a setting, so it raises no "unsaved" badge.
            if (!profile.equalsIgnoringSourceSlot(baseProfile)) return true
        }
        for (baseProfile in bs.knownProfiles) {
            if (knownProfiles.none { it.id == baseProfile.id }) return true
        }
        return false
    }

    fun isProfileDiverged(profileId: Uuid): Boolean {
        val bs = boardState ?: return false
        val currentProfile = knownProfiles.firstOrNull { it.id == profileId }
        val baseProfile = bs.knownProfiles.firstOrNull { it.id == profileId }
        if (currentProfile == null || baseProfile == null) return currentProfile != baseProfile
        return !currentProfile.equalsIgnoringSourceSlot(baseProfile)
    }

    /**
     * True when the user can delete a profile and still keep [MIN_PROFILE_COUNT] profiles.
     *
     * The board refuses a `Profiles` write with no profiles. A write with no profiles has zero
     * length, and the firmware refuses a zero-length OACP WRITE with "Invalid Parameter". The
     * fire-control driver also refuses a roster that decodes to no profiles, because then no
     * active profile can resolve and the marker cannot fire.
     */
    fun canDeleteProfile(): Boolean = knownProfiles.size > MIN_PROFILE_COUNT

    /**
     * Returns a copy whose every profile is clamped to the firmware's enforced ranges (see
     * [MarkerProfile.clampedToFirmwareRanges]). Applied at the board-write boundary so the value
     * sent, the value cached as the baseline, and the editable value all agree — the firmware never
     * silently changes a value the app sends, so no permanent divergence can arise.
     *
     * The profile list is cut to the slots the board has: [supportedProfileCount], and never more
     * than the protocol cap of [DEFAULT_PROFILE_COUNT] (`Profiles.profiles max_count:4`). The board
     * rejects a longer `Profiles` message after it acknowledged the OACP write, so without the cut
     * the app would record a save that the board never kept. An active profile that the cut drops
     * becomes unset, because the board then reads no active slot.
     */
    fun clampedToFirmwareRanges(): Device {
        val slotCount = supportedProfileCount
            .takeIf { it > 0 }
            ?.coerceAtMost(DEFAULT_PROFILE_COUNT)
            ?: DEFAULT_PROFILE_COUNT
        val keptProfiles = knownProfiles.take(slotCount)
        val droppedIds = knownProfiles.drop(slotCount).map { it.id }.toSet()
        return copy(
            knownProfiles = keptProfiles.map { it.clampedToFirmwareRanges() },
            activeProfileId = activeProfileId?.takeUnless { it in droppedIds },
            screenOffDelay = screenOffDelay.clampedScreenOffDelay(),
            // The board REJECTS a whole message that carries an out-of-bounds rectangle, so an
            // illegal window would also drop the other settings in the same write. Clamp it here so
            // the cached optimistic baseline holds what the board will store. The protocol mapper
            // clamps again on its way to the wire, so no path can reach the board unclamped.
            displayWindow = displayWindow?.clampedToPanel(),
        )
    }

    companion object {
        /** Maximum length, in characters, of a user-entered device name. */
        const val MAX_NAME_LENGTH = 10

        /** Maximum length, in characters, of the boot text shown on the marker LCD. */
        const val MAX_BOOT_TEXT_LENGTH = 10

        /**
         * Maximum length, in ASCII characters, of a profile name. The board stores it in a nanopb
         * `char[11]` buffer (one byte reserved for the NUL terminator), so 10 payload bytes; ASCII
         * keeps 10 characters == 10 bytes.
         */
        const val MAX_PROFILE_NAME_LENGTH = 10

        /**
         * Fallback profile-slot count when a board hasn't reported one. Also the protocol
         * hard cap (`Profiles.profiles max_count:4`); a board may support fewer.
         */
        const val DEFAULT_PROFILE_COUNT = 4

        /**
         * The fewest profiles a board keeps. The board refuses a write with fewer (see
         * [canDeleteProfile]).
         */
        const val MIN_PROFILE_COUNT = 1
    }
}

