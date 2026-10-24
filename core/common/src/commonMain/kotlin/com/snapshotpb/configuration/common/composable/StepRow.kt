package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.bluetooth.StepStatus

/**
 * One step of a step list: a status icon and the step text. A [detail] line can go below the text.
 * The connect screen and the firmware update screen use it.
 */
@Composable
internal fun StepRow(
    text: String,
    status: StepStatus,
    detail: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepStatusIcon(status = status)
            Space(16.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (status == StepStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal,
                color = when (status) {
                    StepStatus.COMPLETED -> Color(0xFF4CAF50)
                    StepStatus.FAILED -> MaterialTheme.colorScheme.error
                    StepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onBackground
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
        // The detail line goes below the row, so the icon stays centered on the step text. The
        // start padding (icon 24 dp + space 16 dp) aligns it with the step text.
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
    }
}

@Composable
private fun StepStatusIcon(status: StepStatus) {
    when (status) {
        StepStatus.PENDING -> {
            Canvas(modifier = Modifier.size(24.dp)) {
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.5f),
                    radius = size.minDimension / 2 - 2.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        StepStatus.IN_PROGRESS -> {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        StepStatus.COMPLETED -> {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color(0xFF4CAF50), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val path = Path().apply {
                        moveTo(2.dp.toPx(), 6.dp.toPx())
                        lineTo(5.dp.toPx(), 9.dp.toPx())
                        lineTo(10.dp.toPx(), 3.dp.toPx())
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
        StepStatus.FAILED -> {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
