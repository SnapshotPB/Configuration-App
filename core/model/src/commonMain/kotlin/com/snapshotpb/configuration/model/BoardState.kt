package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class BoardState(
    val bootText: String,
    val activeProfileId: Uuid?,
    val lockoutEnabled: Boolean,
    val knownProfiles: List<MarkerProfile>,
    /**
     * The language the board last reported. `null` in a baseline stored before the app read the
     * language, so the first read that carries one is adopted rather than raised as a conflict.
     */
    val language: BoardLanguage? = null,
    /**
     * The screen brightness the board last reported. `null` in a baseline stored before the app
     * read it, so the first read that carries one is adopted rather than raised as a conflict.
     */
    val screenBrightness: BoardScreenBrightness? = null,
    /** The screen-off delay in seconds the board last reported; `null` when it reported none. */
    val screenOffDelay: Int? = null,
    /**
     * The display window the board last reported. `null` in a baseline stored before the app read
     * it, or when the board reported none, so the first read that carries one is adopted rather
     * than raised as a conflict.
     */
    val displayWindow: DisplayWindow? = null,
)
