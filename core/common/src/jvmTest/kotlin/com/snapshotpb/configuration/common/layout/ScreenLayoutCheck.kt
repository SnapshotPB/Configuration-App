package com.snapshotpb.configuration.common.layout

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.theme.AppTypography
import com.snapshotpb.configuration.common.theme.Theme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The pixel scale of the test window, in pixels per dp. */
internal const val RENDER_DENSITY = 2f

/**
 * The JVM system property that names the output folder for images and metrics. When the property
 * is not set, the harness writes no file, so CI writes no file.
 */
internal const val LAYOUT_RENDER_DIR_PROPERTY = "snapshotpb.layoutRenderDir"

/**
 * One place to scroll to after the first check, and the name of its image.
 *
 * @property toEnd True when the harness must scroll the list to its end after the target scroll.
 */
internal class ScrollTarget(
    val name: String,
    val matcher: SemanticsMatcher,
    val toEnd: Boolean = false,
)

/**
 * The layout measurements of one [screen] at one [size], merged across all scroll positions.
 *
 * @property screen The short name of the screen and state.
 * @property size The display size of the test window.
 * @property twoPanes The result of [fitsTwoPanes] at [size], for the metrics, not a measurement.
 * @property scrollColumnWidthsDp The distinct sorted widths in dp of nodes with vertical scroll.
 * @property textFieldWidthsDp The distinct sorted widths in dp of nodes with `SetText`.
 * @property clippedTexts The distinct sorted texts clipped by bounds or by a height under one line.
 */
internal data class LayoutReport(
    val screen: String,
    val size: DuoSize,
    val twoPanes: Boolean,
    val scrollColumnWidthsDp: List<Int>,
    val textFieldWidthsDp: List<Int>,
    val clippedTexts: List<String>,
)

/** The tolerance for pixel bounds checks, in pixels. */
internal const val BOUNDS_TOLERANCE_PX = 1f
private const val INITIAL_FRAME_MS = 100L
private const val SCROLL_SETTLE_MS = 500L
private const val SCROLL_TO_END_PX = 100_000f
private val METRICS_LOCK = Any()

/**
 * Renders [content] at [size] in a headless Skiko window and checks the semantics tree.
 *
 * The harness checks that no node scrolls sideways and that no node goes past the left edge or the
 * right edge of the window. It also records the widths of scroll columns and text fields, and each
 * clipped text, in the returned [LayoutReport]. The harness writes PNG images and `metrics.tsv`
 * only when [LAYOUT_RENDER_DIR_PROPERTY] is set, so CI writes no file. A scroll target on a screen
 * with an endless animation can reach the time limit, because the scroll turns `autoAdvance` on.
 *
 * @param inspect Optional check that runs after each scroll target, or after the first check when
 *   [scrollTargets] is empty, before the image.
 */
@OptIn(ExperimentalTestApi::class)
internal fun checkScreenLayout(
    screen: String,
    size: DuoSize,
    scrollTargets: List<ScrollTarget> = emptyList(),
    inspect: SemanticsNodeInteractionsProvider.(DuoSize) -> Unit = {},
    content: @Composable () -> Unit,
): LayoutReport {
    val windowWidthPx = size.width.value * RENDER_DENSITY
    val windowHeightPx = size.height.value * RENDER_DENSITY
    val renderDir = System.getProperty(LAYOUT_RENDER_DIR_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)

    val scrollColumnWidths = mutableListOf<Int>()
    val textFieldWidths = mutableListOf<Int>()
    val clippedTexts = mutableListOf<String>()

    runSkikoComposeUiTest(
        size = Size(windowWidthPx, windowHeightPx),
        density = Density(RENDER_DENSITY),
    ) {
        // With autoAdvance off, an infinite animation does not stop waitForIdle().
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme(colorScheme = Theme.lightScheme, typography = AppTypography) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
        mainClock.advanceTimeBy(INITIAL_FRAME_MS)
        waitForIdle()

        inspectSemanticsTree(
            root = onRoot(useUnmergedTree = true).fetchSemanticsNode(),
            screen = screen,
            size = size,
            position = null,
            windowWidthPx = windowWidthPx,
            scrollColumnWidths = scrollColumnWidths,
            textFieldWidths = textFieldWidths,
            clippedTexts = clippedTexts,
        )
        if (scrollTargets.isEmpty()) {
            inspect(size)
        }
        if (renderDir != null) {
            writePng(renderDir, "$screen-${size.label}.png", onRoot().captureToImage().toAwtImage())
        }

        for (target in scrollTargets) {
            // performScrollTo needs clock frames, so turn autoAdvance on for that call only.
            mainClock.autoAdvance = true
            try {
                scrollToTarget(screen, size, target)
            } finally {
                mainClock.autoAdvance = false
            }
            mainClock.advanceTimeBy(SCROLL_SETTLE_MS)
            waitForIdle()

            inspectSemanticsTree(
                root = onRoot(useUnmergedTree = true).fetchSemanticsNode(),
                screen = screen,
                size = size,
                position = target.name,
                windowWidthPx = windowWidthPx,
                scrollColumnWidths = scrollColumnWidths,
                textFieldWidths = textFieldWidths,
                clippedTexts = clippedTexts,
            )
            inspect(size)
            if (renderDir != null) {
                writePng(
                    renderDir,
                    "$screen-${size.label}-${target.name}.png",
                    onRoot().captureToImage().toAwtImage(),
                )
            }
        }
    }

    val report = LayoutReport(
        screen = screen,
        size = size,
        twoPanes = fitsTwoPanes(size.width, size.height),
        scrollColumnWidthsDp = scrollColumnWidths.distinct().sorted(),
        textFieldWidthsDp = textFieldWidths.distinct().sorted(),
        clippedTexts = clippedTexts.distinct().sorted(),
    )
    if (renderDir != null) {
        appendMetrics(renderDir, report)
    }
    return report
}

private fun SemanticsNodeInteractionsProvider.scrollToTarget(
    screen: String,
    size: DuoSize,
    target: ScrollTarget,
) {
    val directMatches = onAllNodes(target.matcher, useUnmergedTree = true)
    if (directMatches.fetchSemanticsNodes().isNotEmpty()) {
        directMatches[0].performScrollTo()
        if (target.toEnd) {
            scrollTargetAncestorToEnd(screen, size, target)
        }
        return
    }
    // A LazyColumn composes only visible items, so scroll each lazy container to find the target.
    val containers = onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true)
    val containerCount = containers.fetchSemanticsNodes().size
    var lastError: AssertionError? = null
    for (index in 0 until containerCount) {
        try {
            containers[index].performScrollToNode(target.matcher)
            if (target.toEnd) {
                scrollTargetAncestorToEnd(screen, size, target)
            }
            return
        } catch (error: AssertionError) {
            // This container does not hold the target node; keep the cause and try the next one.
            lastError = error
        }
    }
    throw AssertionError(
        "$screen (${size.label}): scroll target \"${target.name}\" not found in any scroll container",
        lastError,
    )
}

private fun SemanticsNodeInteractionsProvider.scrollTargetAncestorToEnd(
    screen: String,
    size: DuoSize,
    target: ScrollTarget,
) {
    val targetNode = onNode(target.matcher, useUnmergedTree = true).fetchSemanticsNode()
    val ancestor = generateSequence(targetNode.parent) { it.parent }
        .firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        ?: fail("$screen (${size.label}): scroll target \"${target.name}\" has no ancestor with ScrollBy")
    onNode(
        SemanticsMatcher("id") { it.id == ancestor.id },
        useUnmergedTree = true,
    ).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
        scrollBy(0f, SCROLL_TO_END_PX)
    }
}

private fun inspectSemanticsTree(
    root: SemanticsNode,
    screen: String,
    size: DuoSize,
    position: String?,
    windowWidthPx: Float,
    scrollColumnWidths: MutableList<Int>,
    textFieldWidths: MutableList<Int>,
    clippedTexts: MutableList<String>,
) {
    val context = if (position == null) "$screen (${size.label})" else "$screen (${size.label}, $position)"
    for ((node, insideVerticalScroll) in collectNodes(root)) {
        val nodeLabel = describeNode(node)

        assertNull(
            node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange),
            "$context: node must not scroll sideways ($nodeLabel)",
        )

        val unclippedBounds = node.unclippedBoundsInRoot()
        assertTrue(
            unclippedBounds.left >= -BOUNDS_TOLERANCE_PX,
            "$context: node goes past the left edge (x=${unclippedBounds.left} px, $nodeLabel)",
        )
        assertTrue(
            unclippedBounds.right <= windowWidthPx + BOUNDS_TOLERANCE_PX,
            "$context: node goes past the right edge (right=${unclippedBounds.right} px > $windowWidthPx px, $nodeLabel)",
        )

        if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) {
            scrollColumnWidths += (node.size.width / RENDER_DENSITY).roundToInt()
        }
        if (node.config.getOrNull(SemanticsActions.SetText) != null) {
            textFieldWidths += (node.size.width / RENDER_DENSITY).roundToInt()
        }
        val text = node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            ?.takeIf { it.isNotBlank() }
        if (text != null && isTextClipped(node, unclippedBounds, insideVerticalScroll)) {
            clippedTexts += text
        }
    }
}

/**
 * The bounds of this node in root coordinates, with transforms applied and with no clip.
 */
internal fun SemanticsNode.unclippedBoundsInRoot(): Rect {
    // positionInRoot maps the local origin through transforms. For a node rotated 180 degrees,
    // that point is the bottom-right corner, so positionInRoot.x + size.width adds the width twice.
    val coordinates = layoutInfo.coordinates
    if (!coordinates.isAttached) return Rect.Zero
    return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
}

private fun isTextClipped(
    node: SemanticsNode,
    unclippedBounds: Rect,
    insideVerticalScroll: Boolean,
): Boolean {
    val clippedBounds = node.boundsInRoot
    // Inside a vertical scroll, an off-screen node has empty clipped bounds. Check width only
    // when the node is partly in view.
    val visibleInScroll = clippedBounds.width > 0f && clippedBounds.height > 0f
    val checkWidth = !insideVerticalScroll || visibleInScroll
    val clippedInWidth = checkWidth &&
        unclippedBounds.width - clippedBounds.width > BOUNDS_TOLERANCE_PX
    val clippedInHeight = !insideVerticalScroll &&
        unclippedBounds.height - clippedBounds.height > BOUNDS_TOLERANCE_PX
    if (clippedInWidth || clippedInHeight) return true
    val getTextLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        ?: return false
    val results = mutableListOf<TextLayoutResult>()
    getTextLayout(results)
    return results.any { result ->
        result.lineCount > 0 &&
            node.size.height < (result.getLineBottom(0) - result.getLineTop(0)) - BOUNDS_TOLERANCE_PX
    }
}

private fun collectNodes(root: SemanticsNode): List<Pair<SemanticsNode, Boolean>> {
    val nodes = mutableListOf<Pair<SemanticsNode, Boolean>>()
    val queue = ArrayDeque<Pair<SemanticsNode, Boolean>>()
    queue.add(root to false)
    while (queue.isNotEmpty()) {
        val entry = queue.removeFirst()
        val (node, insideVerticalScroll) = entry
        nodes += entry
        val childInsideVerticalScroll = insideVerticalScroll ||
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        for (child in node.children) {
            queue.add(child to childInsideVerticalScroll)
        }
    }
    return nodes
}

private fun describeNode(node: SemanticsNode): String {
    val parts = buildList {
        node.config.getOrNull(SemanticsProperties.TestTag)
            ?.takeIf { it.isNotBlank() }
            ?.let { add("tag=$it") }
        node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            ?.takeIf { it.isNotBlank() }
            ?.let { add("text=\"$it\"") }
        node.config.getOrNull(SemanticsProperties.EditableText)
            ?.text
            ?.takeIf { it.isNotBlank() }
            ?.let { add("editableText=\"$it\"") }
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
            ?.let { add("contentDescription=\"$it\"") }
    }
    return if (parts.isEmpty()) "node#${node.id}" else parts.joinToString(", ")
}

private fun writePng(renderDir: File, fileName: String, image: BufferedImage) {
    renderDir.mkdirs()
    ImageIO.write(image, "png", File(renderDir, fileName))
}

private fun appendMetrics(renderDir: File, report: LayoutReport) {
    renderDir.mkdirs()
    val clipped = report.clippedTexts.joinToString("|") { text ->
        text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
    val line = listOf(
        report.screen,
        report.size.label,
        report.size.width.value.roundToInt().toString(),
        report.size.height.value.roundToInt().toString(),
        report.twoPanes.toString(),
        report.scrollColumnWidthsDp.joinToString(","),
        report.textFieldWidthsDp.joinToString(","),
        clipped,
    ).joinToString("\t") + "\n"
    synchronized(METRICS_LOCK) {
        File(renderDir, "metrics.tsv").appendText(line)
    }
}
