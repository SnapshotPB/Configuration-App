package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The board model a physical marker board is built for. Mirrors the protocol's
 * `BoardModel`; each value pairs with the board-specific firing config a profile carries.
 * [UNKNOWN] covers an unspecified/forward-compatible board the app doesn't recognize yet.
 */
@Serializable
enum class BoardModel {
    UNKNOWN,
    AUTOCOCKER,
}
