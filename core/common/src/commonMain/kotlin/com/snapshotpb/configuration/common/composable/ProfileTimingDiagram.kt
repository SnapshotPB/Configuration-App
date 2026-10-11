package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.util.formatTenths
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_timing_diagram_label_cdel
import com.snapshotpb.core.resources.profile_timing_diagram_label_coff
import com.snapshotpb.core.resources.profile_timing_diagram_label_con
import com.snapshotpb.core.resources.profile_timing_diagram_label_con_eye
import com.snapshotpb.core.resources.profile_timing_diagram_label_son
import com.snapshotpb.core.resources.profile_timing_diagram_label_toff
import com.snapshotpb.core.resources.profile_timing_diagram_label_ton
import com.snapshotpb.core.resources.profile_timing_diagram_lane_cocking
import com.snapshotpb.core.resources.profile_timing_diagram_lane_sear
import com.snapshotpb.core.resources.profile_timing_diagram_lane_trigger
import com.snapshotpb.core.resources.profile_timing_diagram_value_range
import com.snapshotpb.core.resources.profile_value_ms
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a digital-style timing diagram for one firing cycle, scaled to `TON + CDEL + CON + COFF`.
 * `CON` is the breach-open phase, [MarkerProfile.cockingOpenTenthsMs], so the drawn cycle
 * `CDEL + CON + COFF` is [MarkerProfile.cycleTenthsMs], the cycle of the max BPS.
 * Each lane has its title right-aligned just above the wave, and each annotation has the timing
 * value (in milliseconds) above the arrow line and the segment label below. Each label stays fully
 * inside the canvas: a label that is centred on a short segment near an edge moves inward.
 *
 * The lane order is causal, and it follows the firmware rather than a guess. In the board's fire
 * control, the trigger state machine only mints `TRIGGER_PRESSED_EVENT` once its press-debounce
 * one-shot, armed from `trigger_debounce_on`, expires — so a press is not a shot until `TON` has
 * passed. That event is what moves a firing mode from `READY` to `FIRING`, and the `FIRING` entry
 * effects energise the sear coil, arm the sear-dwell timer and arm the cocking-delay timer in one
 * indivisible list. Three consequences, all of them visible here:
 *
 * 1. The sear starts at `TON`, never at the trigger edge. This is the causality the diagram exists
 *    to show: a pull must be qualified before a shot begins.
 * 2. The sear pulse and the cocking delay start at the SAME instant, because one effect list arms
 *    both timers. They are meant to overlap, and the drawing overlaps them.
 * 3. `triggerDebounceOff` measures from the RELEASE edge, not the press: the trigger machine arms
 *    its release one-shot from `trigger_debounce_off` and only then publishes the released level.
 *
 * The profile fixes every interval here except one — how long the shooter holds the trigger. The
 * drawing therefore shows the shortest shot the firmware will accept: the trigger is held for
 * exactly `TON` and released at once. That is what keeps each drawn segment equal to the number the
 * user typed, instead of letting an invented hold time stretch the lane across the chart.
 *
 * The breach-open phase follows the same rule. When the eye closes the breach
 * ([MarkerProfile.eyeClosesBreach]), the firmware arms the cocking-open timer with the anti-jam
 * timeout, not with the dwell open, and closes the breach when the eye sees a ball for the full eye
 * debounce. That phase has no fixed length. The drawing shows the shortest phase, and its label
 * gives the range from that length to the timeout. A timeout of 500 ms drawn to scale would make
 * every other segment too narrow to read.
 */
@Composable
fun ProfileTimingDiagram(
    profile: MarkerProfile,
    modifier: Modifier = Modifier,
) {
    val signalColor = MaterialTheme.colorScheme.error
    val annotationColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    val laneTitleStyle = MaterialTheme.typography.bodyMedium.copy(
        color = onSurface,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Bold,
    )
    val segmentLabelStyle = MaterialTheme.typography.labelMedium.copy(
        color = annotationColor,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Bold,
    )
    val msTimeStyle = MaterialTheme.typography.labelMedium.copy(
        color = annotationColor,
        fontStyle = FontStyle.Italic,
    )

    val triggerLabel = stringResource(Res.string.profile_timing_diagram_lane_trigger)
    val tOnLabel = stringResource(Res.string.profile_timing_diagram_label_ton)
    val tOffLabel = stringResource(Res.string.profile_timing_diagram_label_toff)
    val searLabel = stringResource(Res.string.profile_timing_diagram_lane_sear)
    val cockingLabel = stringResource(Res.string.profile_timing_diagram_lane_cocking)
    val sonLabel = stringResource(Res.string.profile_timing_diagram_label_son)
    val cdelLabel = stringResource(Res.string.profile_timing_diagram_label_cdel)
    // "On until eye" only when the eye can end the phase. A timeout shorter than the eye debounce
    // always ends it first, and the phase is then a fixed time.
    val conLabel = stringResource(
        if (profile.eyeClosesBreach && profile.cockingOpenLimitTenthsMs > profile.cockingOpenTenthsMs) {
            Res.string.profile_timing_diagram_label_con_eye
        } else {
            Res.string.profile_timing_diagram_label_con
        },
    )
    val coffLabel = stringResource(Res.string.profile_timing_diagram_label_coff)

    // The printed times come from the profile verbatim, through the shared unit string, so the
    // unit follows the locale. The breach-open time is the one exception: it is the phase length
    // that the board uses, because the eye can replace the dwell open (see below).
    val tOnTime = msLabel(profile.triggerDebounceOn)
    val tOffTime = msLabel(profile.triggerDebounceOff)
    val sonTime = msLabel(profile.searSolenoidDwell)
    val cdelTime = msLabel(profile.cockingSolenoidDelay)
    val conTime = openPhaseLabel(profile)
    val coffTime = msLabel(profile.cockingSolenoidDwellClosed)

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val signalStroke = with(density) { 2.5.dp.toPx() }
        val annotationStroke = with(density) { 1.dp.toPx() }
        val guideStroke = with(density) { 1.dp.toPx() }
        val arrowSize = with(density) { 6.dp.toPx() }
        val labelGap = with(density) { 3.dp.toPx() }
        val titleGap = with(density) { 2.dp.toPx() }

        val phases = timingDiagramPhases(profile)
        val cdel = phases.cdel
        val con = phases.con
        val son = phases.son
        val tOff = phases.tOff
        val cycleStart = phases.cycleStart
        val cycleEnd = phases.cycleEnd
        val total = phases.total

        val plotLeft = 0f
        val plotRight = size.width
        val plotWidth = plotRight - plotLeft

        fun xForTime(t: Float) = plotLeft + (t / total) * plotWidth

        val titleRowHeight = with(density) { 18.dp.toPx() }
        val signalHeight = with(density) { 28.dp.toPx() }
        val annotationRowHeight = with(density) { 38.dp.toPx() }
        val laneGap = with(density) { 6.dp.toPx() }

        val triggerTitleTop = 0f
        // The trigger carries two annotations whose segments touch at TON — the press debounce ends
        // exactly where the release debounce begins. Splitting them across an above row and a below
        // row keeps the two labels from landing on top of each other, which one shared row cannot
        // avoid at realistic values (both windows default to 2.0 ms of a ~43 ms axis).
        val triggerAboveAnnoTop = triggerTitleTop + titleRowHeight
        val triggerSignalTop = triggerAboveAnnoTop + annotationRowHeight
        val triggerSignalBottom = triggerSignalTop + signalHeight
        val triggerBelowAnnoTop = triggerSignalBottom

        val searTitleTop = triggerBelowAnnoTop + annotationRowHeight + laneGap
        val searSignalTop = searTitleTop + titleRowHeight
        val searSignalBottom = searSignalTop + signalHeight
        val searBelowAnnoTop = searSignalBottom

        val cockingTitleTop = searBelowAnnoTop + annotationRowHeight + laneGap
        val cockingAboveAnnoTop = cockingTitleTop + titleRowHeight
        val cockingSignalTop = cockingAboveAnnoTop + annotationRowHeight
        val cockingSignalBottom = cockingSignalTop + signalHeight
        val cockingBelowAnnoTop = cockingSignalBottom

        val plotBottom = cockingBelowAnnoTop + annotationRowHeight

        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(
            color = guideColor,
            start = Offset(plotLeft, triggerAboveAnnoTop),
            end = Offset(plotLeft, plotBottom),
            strokeWidth = guideStroke,
            pathEffect = dash,
        )
        drawLine(
            color = guideColor,
            start = Offset(plotRight, triggerAboveAnnoTop),
            end = Offset(plotRight, plotBottom),
            strokeWidth = guideStroke,
            pathEffect = dash,
        )
        // The instant the board qualifies the pull. Every lane below starts from this line, which is
        // what makes the trigger -> sear -> cocking order readable at a glance.
        drawLine(
            color = guideColor,
            start = Offset(xForTime(cycleStart), triggerAboveAnnoTop),
            end = Offset(xForTime(cycleStart), plotBottom),
            strokeWidth = guideStroke,
            pathEffect = dash,
        )

        val signalLift = with(density) { 4.dp.toPx() }

        drawDigitalSignal(
            laneTop = triggerSignalTop,
            laneBottom = triggerSignalBottom,
            plotLeft = plotLeft,
            plotRight = plotRight,
            signalLift = signalLift,
            // The shortest shot the firmware accepts: held for exactly the press debounce, then
            // released. Drawing the raw switch line held any longer would stretch this lane across
            // the chart for a value the profile does not set.
            highSegments = listOf(0f to cycleStart),
            color = signalColor,
            strokeWidth = signalStroke,
            xForTime = ::xForTime,
        )

        drawDigitalSignal(
            laneTop = searSignalTop,
            laneBottom = searSignalBottom,
            plotLeft = plotLeft,
            plotRight = plotRight,
            signalLift = signalLift,
            highSegments = listOf(cycleStart to (cycleStart + son)),
            color = signalColor,
            strokeWidth = signalStroke,
            xForTime = ::xForTime,
        )

        drawDigitalSignal(
            laneTop = cockingSignalTop,
            laneBottom = cockingSignalBottom,
            plotLeft = plotLeft,
            plotRight = plotRight,
            signalLift = signalLift,
            highSegments = listOf((cycleStart + cdel) to (cycleStart + cdel + con)),
            color = signalColor,
            strokeWidth = signalStroke,
            xForTime = ::xForTime,
        )

        drawLaneTitle(
            text = triggerLabel,
            style = laneTitleStyle,
            textMeasurer = textMeasurer,
            rightX = plotRight,
            bottomY = triggerAboveAnnoTop - titleGap,
        )
        drawLaneTitle(
            text = searLabel,
            style = laneTitleStyle,
            textMeasurer = textMeasurer,
            rightX = plotRight,
            bottomY = searSignalTop - titleGap,
        )
        drawLaneTitle(
            text = cockingLabel,
            style = laneTitleStyle,
            textMeasurer = textMeasurer,
            rightX = plotRight,
            bottomY = cockingAboveAnnoTop - titleGap,
        )

        drawSegmentAnnotation(
            startX = xForTime(0f),
            endX = xForTime(cycleStart),
            arrowY = triggerAboveAnnoTop + annotationRowHeight / 2f,
            label = tOnLabel,
            msTime = tOnTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
        drawSegmentAnnotation(
            startX = xForTime(cycleStart),
            endX = xForTime(cycleStart + tOff),
            arrowY = triggerBelowAnnoTop + annotationRowHeight / 2f,
            label = tOffLabel,
            msTime = tOffTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
        drawSegmentAnnotation(
            startX = xForTime(cycleStart),
            endX = xForTime(cycleStart + son),
            arrowY = searBelowAnnoTop + annotationRowHeight / 2f,
            label = sonLabel,
            msTime = sonTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
        drawSegmentAnnotation(
            startX = xForTime(cycleStart),
            endX = xForTime(cycleStart + cdel),
            arrowY = cockingAboveAnnoTop + annotationRowHeight / 2f,
            label = cdelLabel,
            msTime = cdelTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
        drawSegmentAnnotation(
            startX = xForTime(cycleStart + cdel + con),
            endX = xForTime(cycleEnd),
            arrowY = cockingAboveAnnoTop + annotationRowHeight / 2f,
            label = coffLabel,
            msTime = coffTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
        drawSegmentAnnotation(
            startX = xForTime(cycleStart + cdel),
            endX = xForTime(cycleStart + cdel + con),
            arrowY = cockingBelowAnnoTop + annotationRowHeight / 2f,
            label = conLabel,
            msTime = conTime,
            labelStyle = segmentLabelStyle,
            msTimeStyle = msTimeStyle,
            textMeasurer = textMeasurer,
            color = annotationColor,
            strokeWidth = annotationStroke,
            arrowSize = arrowSize,
            labelGap = labelGap,
        )
    }
}

/** A time in tenths of a millisecond, as the localized "<value> ms" label. */
@Composable
private fun msLabel(tenthsOfMs: Int): String =
    stringResource(Res.string.profile_value_ms, formatTenths(tenthsOfMs))

/**
 * The time label of the breach-open segment. When the eye closes the breach, the phase is not a
 * fixed time, so the label gives the range from the drawn shortest phase to the anti-jam timeout.
 */
@Composable
private fun openPhaseLabel(profile: MarkerProfile): String {
    val shortest = msLabel(profile.cockingOpenTenthsMs)
    if (profile.cockingOpenLimitTenthsMs <= profile.cockingOpenTenthsMs) return shortest
    return stringResource(
        Res.string.profile_timing_diagram_value_range,
        shortest,
        msLabel(profile.cockingOpenLimitTenthsMs),
    )
}

/**
 * The drawn phases of the diagram, in tenths of a millisecond, on the time axis of the canvas.
 *
 * The cocking phases are [MarkerProfile.cockingSolenoidDelay], [MarkerProfile.cockingOpenTenthsMs]
 * and [MarkerProfile.cockingSolenoidDwellClosed], so the drawn cycle is
 * [MarkerProfile.cycleTenthsMs], the same cycle that [MarkerProfile.maxBpsForTiming] uses.
 *
 * These values are for the canvas only. A 0 setting would collapse its segment to zero width and
 * take the wave edge and the arrow with it, so each segment has a floor of one tenth of a
 * millisecond. The sear also has a ceiling of one cycle, so that a dwell longer than the cycle
 * cannot go off the axis. The labels never show these adjustments: a floored segment still prints
 * the real 0.0 ms above it.
 */
internal data class TimingDiagramPhases(
    val tOn: Float,
    val tOff: Float,
    val son: Float,
    val cdel: Float,
    val con: Float,
    val coff: Float,
) {
    /** The cocking cycle: the delay, the breach-open phase and the closed dwell. */
    val cycle: Float get() = cdel + con + coff

    /**
     * The press debounce qualifies the pull at TON, where the firmware enters FIRING, so the
     * firing cycle starts at TON and not at the trigger edge.
     */
    val cycleStart: Float get() = tOn

    val cycleEnd: Float get() = cycleStart + cycle

    /**
     * The length of the axis. The shortest accepted shot releases the trigger at TON, so the
     * release debounce runs from TON to TON + TOFF. That is usually inside the cycle, but a long
     * release debounce can go past the cycle end, and the axis then extends to include it.
     */
    val total: Float get() = maxOf(cycleEnd, cycleStart + tOff)
}

internal fun timingDiagramPhases(profile: MarkerProfile): TimingDiagramPhases {
    val cdel = profile.cockingSolenoidDelay.toFloat().coerceAtLeast(1f)
    val con = profile.cockingOpenTenthsMs.toFloat().coerceAtLeast(1f)
    val coff = profile.cockingSolenoidDwellClosed.toFloat().coerceAtLeast(1f)
    return TimingDiagramPhases(
        tOn = profile.triggerDebounceOn.toFloat().coerceAtLeast(1f),
        tOff = profile.triggerDebounceOff.toFloat().coerceAtLeast(1f),
        son = profile.searSolenoidDwell.toFloat().coerceAtLeast(1f).coerceAtMost(cdel + con + coff),
        cdel = cdel,
        con = con,
        coff = coff,
    )
}

/**
 * The start of a label of [length] in the range `0..limit`. A label that goes across an edge moves
 * the shortest distance that puts all of it inside the range. A label longer than [limit] starts
 * at 0.
 */
internal fun boundedLabelStart(start: Float, length: Float, limit: Float): Float =
    start.coerceIn(0f, (limit - length).coerceAtLeast(0f))

/**
 * Measures [text] as one line. A line wider than the canvas ends with an ellipsis, so that the
 * label always fits between the two edges.
 */
private fun DrawScope.measureLabel(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): TextLayoutResult = textMeasurer.measure(
    text = text,
    style = style,
    overflow = TextOverflow.Ellipsis,
    maxLines = 1,
    constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(0)),
)

/**
 * Draws [layout] with its top-left corner at ([x], [y]), moved as necessary to keep all of it
 * inside the canvas. A label centred on a short segment at an edge of the axis, for example the
 * TON label at the start, otherwise goes across that edge and the view clips it.
 */
private fun DrawScope.drawLabel(layout: TextLayoutResult, x: Float, y: Float) {
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = boundedLabelStart(x, layout.size.width.toFloat(), size.width),
            y = boundedLabelStart(y, layout.size.height.toFloat(), size.height),
        ),
    )
}

private fun DrawScope.drawLaneTitle(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    rightX: Float,
    bottomY: Float,
) {
    val layout = measureLabel(text, style, textMeasurer)
    drawLabel(
        layout = layout,
        x = rightX - layout.size.width,
        y = bottomY - layout.size.height,
    )
}

private fun DrawScope.drawDigitalSignal(
    laneTop: Float,
    laneBottom: Float,
    plotLeft: Float,
    plotRight: Float,
    signalLift: Float,
    highSegments: List<Pair<Float, Float>>,
    color: Color,
    strokeWidth: Float,
    xForTime: (Float) -> Float,
) {
    val low = laneBottom
    val high = laneTop + signalLift
    val path = Path()
    path.moveTo(plotLeft, low)
    var currentX = plotLeft
    for ((startT, endT) in highSegments) {
        val sx = xForTime(startT)
        val ex = xForTime(endT)
        if (sx > currentX) path.lineTo(sx, low)
        path.lineTo(sx, high)
        path.lineTo(ex, high)
        path.lineTo(ex, low)
        currentX = ex
    }
    if (currentX < plotRight) path.lineTo(plotRight, low)
    drawPath(path, color = color, style = Stroke(strokeWidth))
}

private fun DrawScope.drawSegmentAnnotation(
    startX: Float,
    endX: Float,
    arrowY: Float,
    label: String,
    msTime: String,
    labelStyle: TextStyle,
    msTimeStyle: TextStyle,
    textMeasurer: TextMeasurer,
    color: Color,
    strokeWidth: Float,
    arrowSize: Float,
    labelGap: Float,
) {
    val mid = (startX + endX) / 2f

    val msLayout = measureLabel(msTime, msTimeStyle, textMeasurer)
    drawLabel(
        layout = msLayout,
        x = mid - msLayout.size.width / 2f,
        y = arrowY - msLayout.size.height - labelGap,
    )

    drawArrowedRange(
        startX = startX,
        endX = endX,
        y = arrowY,
        color = color,
        strokeWidth = strokeWidth,
        arrowSize = arrowSize,
    )

    val labelLayout = measureLabel(label, labelStyle, textMeasurer)
    drawLabel(
        layout = labelLayout,
        x = mid - labelLayout.size.width / 2f,
        y = arrowY + labelGap,
    )
}

private fun DrawScope.drawArrowedRange(
    startX: Float,
    endX: Float,
    y: Float,
    color: Color,
    strokeWidth: Float,
    arrowSize: Float,
) {
    if (endX - startX < 1f) return
    drawLine(color, Offset(startX, y), Offset(endX, y), strokeWidth)
    drawLine(color, Offset(startX, y), Offset(startX + arrowSize, y - arrowSize / 2f), strokeWidth)
    drawLine(color, Offset(startX, y), Offset(startX + arrowSize, y + arrowSize / 2f), strokeWidth)
    drawLine(color, Offset(endX, y), Offset(endX - arrowSize, y - arrowSize / 2f), strokeWidth)
    drawLine(color, Offset(endX, y), Offset(endX - arrowSize, y + arrowSize / 2f), strokeWidth)
}
