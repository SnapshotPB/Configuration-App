package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.grip_frame_86
import com.snapshotpb.core.resources.grip_frame_e1
import com.snapshotpb.core.resources.grip_frame_e2
import org.jetbrains.compose.resources.stringResource

/**
 * Localized name for a [GripFrame]. The board firmware knows no frame by name, so the app supplies
 * the name in the app's own language.
 */
@Composable
fun gripFrameLabel(frame: GripFrame): String = stringResource(
    when (frame) {
        GripFrame.E1 -> Res.string.grip_frame_e1
        GripFrame.E2 -> Res.string.grip_frame_e2
        GripFrame.FRAME_86 -> Res.string.grip_frame_86
    }
)
