package com.snapshotpb.configuration.firmware

/**
 * The release gate of the firmware update (`docs/firmware-update-plan.md` §2.6, decision 2), and
 * the firmware site of the build. Each app binds one value.
 *
 * Until a firmware release has the image signature and swap using move (work item 5), a release
 * build of the app shows the board version and the hardware target, but it offers no update: the
 * status reason is [FirmwareUnavailableReason.NOT_YET_RELEASED]. A debug build, and a build that
 * reads a local firmware site on the bench (work item 12), offer the update.
 *
 * @property debugBuild True in a debug build. Android and iOS bind it in their debug and release
 *   source sets, next to the binding of the simulated board.
 * @property siteUrl The root URL of the firmware site. A bench build gives the URL of a local site.
 */
data class FirmwareUpdatePolicy(
    val debugBuild: Boolean = false,
    val siteUrl: String = FIRMWARE_SITE_URL,
) {
    /**
     * True when [siteUrl] is not the public firmware site. Each other URL counts as a bench site,
     * also a URL that is not on the local network: only a person who starts the app with the bench
     * argument can set it.
     */
    val usesLocalSite: Boolean
        get() = siteUrl.trimEnd('/') != FIRMWARE_SITE_URL

    /** True when the app can offer an update in this build. */
    val updatesEnabled: Boolean
        get() = UPDATES_RELEASED || debugBuild || usesLocalSite

    companion object {
        /**
         * THE RELEASE GATE. Set it to true in the app release that offers the firmware update to
         * every user, and only after a firmware release with the image signature and swap using
         * move is on the firmware site.
         */
        const val UPDATES_RELEASED: Boolean = false
    }
}

/**
 * An optional replacement of the firmware feed. The debug builds of Android and iOS bind one in
 * `core:fake-board`: it gives a feed of a simulated site while the simulated board is active, so
 * the Demo entry shows the whole update with no network. No release build binds one.
 */
fun interface FirmwareFeedOverride {
    /** The feed to use now, or `null` for the feed of the firmware site. */
    fun feedOrNull(): FirmwareFeed?
}
