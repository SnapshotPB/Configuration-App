package com.snapshotpb.configuration.common.screen.profile.composable

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.MarkerProfileItem
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.button_delete
import com.snapshotpb.core.resources.delete_icon
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private enum class MarkerProfileCardDragValue { Settled, Revealed }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkerProfileCard(
    index: Int,
    activeProfile: Boolean,
    profile: MarkerProfile,
    isDiverged: Boolean = false,
    /** False for the last profile: the board keeps at least one, so the swipe shows no delete. */
    deletable: Boolean = true,
    onProfileSelected: (profile: MarkerProfile) -> Unit,
    onProfileDeleted: (profile: MarkerProfile) -> Unit,
) {
    Column {
        val density = LocalDensity.current
        val actionWidth = 80.dp

        // Keyed on the profile content, not only on the list key. A delete moves each later
        // profile into the slot id of the profile before it, so the LazyColumn gives the next
        // profile the state of the deleted card. Without this key, that profile showed with its
        // delete action open.
        val state = remember(profile, deletable) {
            AnchoredDraggableState(
                initialValue = MarkerProfileCardDragValue.Settled,
            )
        }

        val actionWidthPx = with(density) { actionWidth.toPx() }
        val anchors = remember(actionWidthPx) {
            DraggableAnchors {
                MarkerProfileCardDragValue.Settled at 0f
                MarkerProfileCardDragValue.Revealed at -actionWidthPx
            }
        }

        SideEffect {
            state.updateAnchors(anchors)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = deletable,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state,
                        positionalThreshold = { distance: Float -> distance * 0.5f },
                        animationSpec = tween(),
                    )
                )
        ) {
            // Background Action (Delete). The last profile has no delete action at all, so
            // an accessibility service cannot reach a hidden delete button.
            if (deletable) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    IconButton(
                        onClick = { onProfileDeleted(profile) },
                        modifier = Modifier
                            .width(actionWidth)
                            .padding(end = 16.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.delete_icon),
                            contentDescription = stringResource(Res.string.button_delete),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Foreground Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(
                            x = state.offset.roundToInt(),
                            y = 0
                        )
                    }
            ) {
                MarkerProfileItem(
                    index = index,
                    activeProfile = activeProfile,
                    profile = profile,
                    isDiverged = isDiverged,
                    onClick = { onProfileSelected(profile) }
                )
            }
        }
    }
}
