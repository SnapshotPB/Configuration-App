package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.board_language_de
import com.snapshotpb.core.resources.board_language_en
import com.snapshotpb.core.resources.board_language_es
import com.snapshotpb.core.resources.board_language_fr
import org.jetbrains.compose.resources.stringResource

/**
 * Localized name for a [BoardLanguage] (e.g. "German" rather than the raw code "DE"). The protocol
 * carries only the language code, so the app supplies the name in the app's own language.
 */
@Composable
fun boardLanguageLabel(language: BoardLanguage): String = stringResource(
    when (language) {
        BoardLanguage.EN -> Res.string.board_language_en
        BoardLanguage.ES -> Res.string.board_language_es
        BoardLanguage.DE -> Res.string.board_language_de
        BoardLanguage.FR -> Res.string.board_language_fr
    }
)
