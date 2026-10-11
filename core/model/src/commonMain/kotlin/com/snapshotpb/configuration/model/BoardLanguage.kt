package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The language the board draws its OWN menu text in. Mirrors the protocol's `Language` enum, which
 * lists only the languages the firmware draws. It does not constrain user text: boot text and
 * profile names keep their own limits.
 *
 * A device holds `null` when the board has not reported a language: firmware from before the field
 * existed omits it, which decodes as `LANGUAGE_UNSPECIFIED`. A `null` write leaves the board's
 * language unchanged.
 */
@Serializable
enum class BoardLanguage {
    EN,
    ES,
    DE,
    FR,
}
