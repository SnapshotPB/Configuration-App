package com.snapshotpb.configuration.common.screen.device.composable

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
import com.snapshotpb.configuration.common.composable.ClickableCard
import com.snapshotpb.configuration.common.composable.MarkerDeviceItem
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.button_delete
import com.snapshotpb.core.resources.delete_icon
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private enum class KnownDeviceCardDragValue { Settled, Revealed }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KnownDeviceCard(
    first: Boolean,
    device: Device,
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
) {
    Column {
        if (!first) Space(8.dp)

        val density = LocalDensity.current
        val actionWidth = 80.dp

        val state = remember {
            AnchoredDraggableState(
                initialValue = KnownDeviceCardDragValue.Settled,
            )
        }

        val actionWidthPx = with(density) { actionWidth.toPx() }
        val anchors = remember(actionWidthPx) {
            DraggableAnchors {
                KnownDeviceCardDragValue.Settled at 0f
                KnownDeviceCardDragValue.Revealed at -actionWidthPx
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
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state,
                        positionalThreshold = { distance: Float -> distance * 0.5f },
                        animationSpec = tween(),
                    )
                )
        ) {
            // Background Action (Delete)
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
                    onClick = { onDeviceDeleted(device) },
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
                ClickableCard(
                    onClick = { onDeviceSelected(device) }
                ) {
                    MarkerDeviceItem(
                        photoPath = device.photoPath,
                        device = device,
                    )
                }
            }
        }
    }
}
