package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Open height of the portrait photo header. */
private val PHOTO_HEADER_HEIGHT = 260.dp

/** The dark scrim behind the title while the photo is open. */
private val TITLE_SCRIM = Color.Black.copy(alpha = 0.3f)

/**
 * Portrait screen body: a photo header with a title bar, above one column that scrolls.
 *
 * The scroll first folds the photo away, then moves the column. The title bar stays in view
 * when the photo is gone, so the screen keeps its name, and it turns opaque as the photo goes.
 * The fold goes only as far as the column needs: a column that fits below the open photo
 * keeps the photo, and a column that overflows by a little folds the photo by that little.
 * The photo comes back only when the column is at the top again. A fold in `onPreScroll` and
 * an unfold in `onPostScroll` keep these two steps apart: an unfold in `onPreScroll` would
 * pull the photo back down in the middle of the column.
 *
 * The fold is read only in the layout and draw passes, so a scroll frame causes no
 * recomposition. When the column gets shorter (for example, a section hides), the fold that
 * it needs gets smaller, and the photo opens by the same amount.
 */
@Composable
fun CollapsingPhotoHeaderColumn(
    photoPath: String,
    title: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val openHeightPx = with(density) { PHOTO_HEADER_HEIGHT.toPx() }
    val collapse = remember(openHeightPx) { PhotoHeaderCollapse(openHeightPx) }
    val isElevated by remember(collapse, scrollState) {
        derivedStateOf { scrollState.value > 0 || collapse.effectiveCollapsePx > 0f }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { collapse.containerHeightPx = it.height }
            .nestedScroll(collapse.connection)
    ) {
        Surface(
            modifier = Modifier.zIndex(1f),
            shadowElevation = if (isElevated) 4.dp else 0.dp,
            tonalElevation = if (isElevated) 4.dp else 0.dp,
        ) {
            Box(
                // The header height is read in the layout pass, so a scroll frame moves the
                // header without a recomposition. The photo keeps its full height and stays
                // anchored to the bottom edge, so it slides up out of view as the box shrinks
                // instead of a squash. clipToBounds() cuts off the part above the box.
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val height = collapse.headerHeightPx.roundToInt()
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = height, maxHeight = height)
                        )
                        layout(placeable.width, height) { placeable.place(0, 0) }
                    }
                    .clipToBounds()
            ) {
                DeviceBadge(
                    photoPath = photoPath,
                    // unbounded = true measures the photo at its open height inside the smaller
                    // box, and Alignment.Bottom puts the overflow above the box.
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                        .height(PHOTO_HEADER_HEIGHT),
                )

                HeaderTitleBar(
                    title = title,
                    foldFraction = { collapse.foldFraction },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .onSizeChanged { collapse.titleBarHeightPx = it.height },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                // After verticalScroll, so this is the full height of the content, not the
                // height of the visible part.
                .onSizeChanged { collapse.contentHeightPx = it.height },
            content = content,
        )
    }
}

/**
 * Title bar over the bottom edge of the photo. Open, it is a dark scrim with white text over
 * the photo. Folded, it is an opaque surface with the usual text color, so no part of the
 * photo shows through the bar when the photo is gone. [foldFraction] moves it between the two.
 *
 * [foldFraction] is a function, and only the draw pass calls it. A change of the fold redraws
 * the bar and does not recompose it.
 *
 * The title has one line. A long title at a large font scale could otherwise make the bar
 * taller than the header, and the fold would stop.
 */
@Composable
private fun HeaderTitleBar(title: String, foldFraction: () -> Float, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(lerp(TITLE_SCRIM, surface, foldFraction())) }
    ) {
        BasicText(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = ColorProducer { lerp(Color.White, onSurface, foldFraction()) },
            modifier = Modifier.padding(8.dp),
        )
    }
}

/**
 * Scroll bookkeeping for the photo header. Two limits cap the fold: the title bar height, so
 * the title stays in view, and the column overflow, so the photo folds only as far as the
 * column needs. The overflow comes from the measured heights of the screen body and of the
 * content, so it follows the content when the content gets shorter or taller.
 */
@Stable
private class PhotoHeaderCollapse(
    private val openHeightPx: Float,
) {
    /** Height the photo has given to the scroll, in pixels. Zero is fully open. */
    var collapsePx by mutableFloatStateOf(0f)

    /** Measured height of the title bar. The fold stops here, so the title stays in view. */
    var titleBarHeightPx by mutableIntStateOf(0)

    /** Measured height of the whole screen body: the header plus the column viewport. */
    var containerHeightPx by mutableIntStateOf(UNMEASURED)

    /** Measured full height of the column content. */
    var contentHeightPx by mutableIntStateOf(UNMEASURED)

    /** The fold that hides the whole photo and leaves only the title bar. */
    private val fullFoldPx: Float
        get() = (openHeightPx - titleBarHeightPx).coerceAtLeast(0f)

    /**
     * The fold the column needs: the overflow of its content below the open photo. The
     * container height does not change with the fold, so the viewport with the photo open is
     * the container less the open header. Before the first layout the heights are unknown, so
     * only [fullFoldPx] applies.
     */
    private val neededFoldPx: Float
        get() {
            if (containerHeightPx == UNMEASURED || contentHeightPx == UNMEASURED) return fullFoldPx
            val openViewportPx = containerHeightPx - openHeightPx
            return (contentHeightPx - openViewportPx).coerceAtLeast(0f)
        }

    private val maxCollapsePx: Float
        get() = minOf(fullFoldPx, neededFoldPx)

    /** The fold in effect: the stored fold, held inside the limits that apply now. */
    val effectiveCollapsePx: Float
        get() = collapsePx.coerceIn(0f, maxCollapsePx)

    /** Current header height. A title bar that grows, or content that shrinks, opens the header. */
    val headerHeightPx: Float
        get() = openHeightPx - effectiveCollapsePx

    /** Zero with the photo open, one with the photo gone. A partial fold keeps a scrim. */
    val foldFraction: Float
        get() = if (fullFoldPx > 0f) (effectiveCollapsePx / fullFoldPx).coerceIn(0f, 1f) else 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f) return Offset.Zero
            return Offset(0f, take(available.y))
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.y <= 0f) return Offset.Zero
            return Offset(0f, take(available.y))
        }
    }

    /** Moves the fold by [deltaY] inside its limits and returns the part of [deltaY] it used. */
    private fun take(deltaY: Float): Float {
        val limit = maxCollapsePx
        val current = collapsePx.coerceIn(0f, limit)
        val collapsed = (current - deltaY).coerceIn(0f, limit)
        collapsePx = collapsed
        return current - collapsed
    }

    private companion object {
        /** A height before the first layout has measured it. */
        const val UNMEASURED = -1
    }
}
