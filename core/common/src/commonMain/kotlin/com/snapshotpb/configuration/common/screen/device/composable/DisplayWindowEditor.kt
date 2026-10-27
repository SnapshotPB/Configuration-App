package com.snapshotpb.configuration.common.screen.device.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.RepeatingIconButton
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.model.DISPLAY_PANEL_HEIGHT
import com.snapshotpb.configuration.model.DISPLAY_PANEL_WIDTH
import com.snapshotpb.configuration.model.DISPLAY_WINDOW_MIN_HEIGHT
import com.snapshotpb.configuration.model.DISPLAY_WINDOW_MIN_WIDTH
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_display_window_drawing
import com.snapshotpb.core.resources.configure_device_screen_display_window_height_label
import com.snapshotpb.core.resources.configure_device_screen_display_window_hint
import com.snapshotpb.core.resources.configure_device_screen_display_window_summary
import com.snapshotpb.core.resources.configure_device_screen_display_window_width_label
import com.snapshotpb.core.resources.profile_button_decrement
import com.snapshotpb.core.resources.profile_button_increment
import org.jetbrains.compose.resources.stringResource

/**
 * Shape of the panel, as width divided by height. The drawing takes the whole width of the card and
 * keeps this ratio, so one panel pixel stays square at every width.
 */
private const val PANEL_ASPECT = DISPLAY_PANEL_WIDTH.toFloat() / DISPLAY_PANEL_HEIGHT

/** Half the length of one arm of the move mark, in panel pixels. */
private const val MOVE_MARK_ARM_PANEL_PIXELS = 4

/** Smallest line the drawing puts on the screen, in device pixels. */
private const val MIN_STROKE = 1f

/** Distance between two grid lines, in panel pixels. */
private const val GRID_STEP = 8

/*
 * The drawing uses FIXED colours, not theme roles. It shows a physical panel: the part the grip
 * frame covers is dark glass, and the part it reveals is lit. Theme roles gave too little contrast
 * between the two, and the section swaps its container colour on a divergence, which moved the
 * contrast again. Fixed colours hold the same strong difference in every theme and in every state.
 */

/** The part of the panel that the grip frame covers. */
private val PANEL_COVERED_COLOR = Color(0xFF0E1216)

/** Faint grid on the covered glass. It shows the user how far the window moved. */
private val PANEL_GRID_COLOR = Color(0xFF2B333B)

/** The part of the panel that the grip frame reveals. */
private val WINDOW_FILL_COLOR = Color(0xFF00E5FF)

/** Edge of the revealed part. White reads against the lit fill and against the dark glass. */
private val WINDOW_BORDER_COLOR = Color.White

/** The mark at the centre of the window that shows the user that a drag moves it. */
private val WINDOW_MOVE_MARK_COLOR = Color(0xFF06303A)

/**
 * The drawing of the board's panel with the visible window over it, and the controls that move and
 * size that window when a custom frame is selected.
 *
 * THE DRAWING IS IN THE PANEL'S OWN FRAME: [DISPLAY_PANEL_WIDTH] across by [DISPLAY_PANEL_HEIGHT]
 * down. So [DisplayWindow.x] and [DisplayWindow.w] run across the drawing, and [DisplayWindow.y]
 * and [DisplayWindow.h] run down it. The number the user reads, the edge they drag, and the value
 * on the wire are then the same thing, and nothing maps through the 90 degree mount.
 *
 * Every change goes through [DisplayWindow.clampedToPanel], so the value this editor reports is
 * always one the board accepts. The board rejects a whole message that carries an out-of-bounds
 * rectangle, so an illegal value must never reach the write.
 *
 * @param window the rectangle to draw and edit.
 * @param onWindowChange receives each new rectangle, already clamped.
 */
@Composable
internal fun DisplayWindowEditor(
    window: DisplayWindow,
    onWindowChange: (DisplayWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DisplayWindowDrawing(
            window = window,
            onWindowChange = onWindowChange,
        )

        Space(12.dp)

        Text(
            text = stringResource(
                Res.string.configure_device_screen_display_window_summary,
                window.w,
                window.h,
                window.x,
                window.y,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Space(8.dp)

        ExtentStepper(
            label = stringResource(Res.string.configure_device_screen_display_window_width_label),
            value = window.w,
            minimum = DISPLAY_WINDOW_MIN_WIDTH,
            maximum = DISPLAY_PANEL_WIDTH,
            onValueChange = { onWindowChange(window.copy(w = it).clampedToPanel()) },
        )

        ExtentStepper(
            label = stringResource(Res.string.configure_device_screen_display_window_height_label),
            value = window.h,
            minimum = DISPLAY_WINDOW_MIN_HEIGHT,
            maximum = DISPLAY_PANEL_HEIGHT,
            onValueChange = { onWindowChange(window.copy(h = it).clampedToPanel()) },
        )

        Text(
            text = stringResource(Res.string.configure_device_screen_display_window_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Draws the whole panel and marks the part the grip frame reveals. A drag moves the marked part,
 * one panel pixel at a time.
 */
@Composable
private fun DisplayWindowDrawing(
    window: DisplayWindow,
    onWindowChange: (DisplayWindow) -> Unit,
) {
    // The panel edge is the one theme colour here. It must read against the card behind it, which
    // the theme owns, while everything inside the panel keeps its own fixed colours.
    val panelBorderColor = MaterialTheme.colorScheme.outline
    val drawingLabel = stringResource(Res.string.configure_device_screen_display_window_drawing)

    // The drag callback must see the rectangle of the current composition, not the one captured
    // when pointerInput started, or a second drag would resume from a stale position.
    val currentWindow by rememberUpdatedState(window)
    val currentOnWindowChange by rememberUpdatedState(onWindowChange)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PANEL_ASPECT)
            .semantics { contentDescription = drawingLabel }
            .pointerInput(Unit) {
                // The gesture carries its own rectangle and its own remainder. Compose delivers
                // several pointer samples per frame, and a rectangle read from the composition
                // would not move between them, so a fast drag would fall behind the finger.
                var dragged = DisplayWindow.FULL_PANEL
                var remainder = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        dragged = currentWindow
                        remainder = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // The drawing takes the width it is given, so one panel pixel is read from
                        // the current size. A new width then moves the finger the same distance.
                        val acrossPx = size.width.toFloat() / DISPLAY_PANEL_WIDTH
                        val downPx = size.height.toFloat() / DISPLAY_PANEL_HEIGHT
                        remainder += dragAmount
                        val stepX = (remainder.x / acrossPx).toInt()
                        val stepY = (remainder.y / downPx).toInt()
                        if (stepX != 0 || stepY != 0) {
                            remainder = Offset(
                                x = remainder.x - stepX * acrossPx,
                                y = remainder.y - stepY * downPx,
                            )
                            dragged = dragged.copy(
                                x = dragged.x + stepX,
                                y = dragged.y + stepY,
                            ).clampedToPanel()
                            currentOnWindowChange(dragged)
                        }
                    },
                )
            },
    ) {
        val across = size.width / DISPLAY_PANEL_WIDTH
        val down = size.height / DISPLAY_PANEL_HEIGHT
        // Every line comes from the drawn panel pixel, so the drawing holds its weight at any width.
        val borderStrokePx = (across / 2).coerceAtLeast(MIN_STROKE)
        val gridStrokePx = (across / 4).coerceAtLeast(MIN_STROKE)
        val moveMarkArmPx = across * MOVE_MARK_ARM_PANEL_PIXELS

        drawRect(color = PANEL_COVERED_COLOR)
        drawPanelGrid(strokeWidth = gridStrokePx)

        val topLeft = Offset(x = window.x * across, y = window.y * down)
        val windowSize = Size(width = window.w * across, height = window.h * down)
        drawRect(color = WINDOW_FILL_COLOR, topLeft = topLeft, size = windowSize)
        drawRect(
            color = WINDOW_BORDER_COLOR,
            topLeft = topLeft,
            size = windowSize,
            style = Stroke(width = borderStrokePx),
        )
        drawMoveMark(
            center = Offset(
                x = topLeft.x + windowSize.width / 2,
                y = topLeft.y + windowSize.height / 2,
            ),
            arm = moveMarkArmPx,
            strokeWidth = borderStrokePx,
        )

        // The panel edge comes last, so no fill covers it. A stroke draws half of its width on
        // each side of the line, so the edge moves in by half, and the canvas clips none of it.
        val halfStroke = borderStrokePx / 2
        drawRect(
            color = panelBorderColor,
            topLeft = Offset(x = halfStroke, y = halfStroke),
            size = Size(width = size.width - borderStrokePx, height = size.height - borderStrokePx),
            style = Stroke(width = borderStrokePx),
        )
    }
}

/**
 * Draws the faint grid on the covered glass, one line every [GRID_STEP] panel pixels. The spacing
 * comes from the canvas, so it always agrees with the panel the caller drew.
 */
private fun DrawScope.drawPanelGrid(strokeWidth: Float) {
    val across = size.width / DISPLAY_PANEL_WIDTH
    val down = size.height / DISPLAY_PANEL_HEIGHT
    for (column in GRID_STEP until DISPLAY_PANEL_WIDTH step GRID_STEP) {
        val atX = column * across
        drawLine(
            color = PANEL_GRID_COLOR,
            start = Offset(x = atX, y = 0f),
            end = Offset(x = atX, y = size.height),
            strokeWidth = strokeWidth,
        )
    }
    for (row in GRID_STEP until DISPLAY_PANEL_HEIGHT step GRID_STEP) {
        val atY = row * down
        drawLine(
            color = PANEL_GRID_COLOR,
            start = Offset(x = 0f, y = atY),
            end = Offset(x = size.width, y = atY),
            strokeWidth = strokeWidth,
        )
    }
}

/**
 * Draws a cross at the centre of the window, so the user sees that a drag moves the window.
 *
 * The mark sits at the CENTRE and not at the corners. A corner mark is the usual sign of a resize
 * grip, but the drag changes the position only. The two steppers change the size.
 */
private fun DrawScope.drawMoveMark(center: Offset, arm: Float, strokeWidth: Float) {
    drawLine(
        color = WINDOW_MOVE_MARK_COLOR,
        start = Offset(x = center.x - arm, y = center.y),
        end = Offset(x = center.x + arm, y = center.y),
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = WINDOW_MOVE_MARK_COLOR,
        start = Offset(x = center.x, y = center.y - arm),
        end = Offset(x = center.x, y = center.y + arm),
        strokeWidth = strokeWidth,
    )
}

/** One labelled extent with a decrement and an increment button, bounded to the board's range. */
@Composable
private fun ExtentStepper(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RepeatingIconButton(
            onClick = { onValueChange((value - 1).coerceIn(minimum, maximum)) }
        ) {
            Text(
                text = stringResource(Res.string.profile_button_decrement),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        RepeatingIconButton(
            onClick = { onValueChange((value + 1).coerceIn(minimum, maximum)) }
        ) {
            Text(
                text = stringResource(Res.string.profile_button_increment),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
