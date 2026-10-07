package com.zhuanz.flymemotion.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.core.SpringCurve
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

val CloseColor = Color(0xFF3482FF)
val ReboundColor = Color(0xFFF5A524)

/**
 * The two settle curves the current draft produces, from rest (core SpringCurve). The last played
 * release, with its real fling velocity, is drawn on top and revealed in step with the preview.
 */
@Composable
fun SpringChart(
    stiffness: Float,
    expandDamping: Float,
    closeDamping: Float,
    played: Release?,
    reveal: Float,
    modifier: Modifier = Modifier,
) {
    val rebound = remember(stiffness, expandDamping) { SpringCurve(stiffness, expandDamping, 0f, 1000f) }
    val close = remember(stiffness, closeDamping) { SpringCurve(stiffness, closeDamping, 0f, 1000f) }
    val grid = MiuixTheme.colorScheme.dividerLine
    val label = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 6.dp)) {
            val span = maxOf(rebound.durationMillis, close.durationMillis, played?.curve?.durationMillis ?: 0L).toFloat()
            val top = 1.18f
            fun y(v: Double) = size.height * (1f - (v.toFloat() / top))
            // target line and baseline
            drawLine(grid, Offset(0f, y(1.0)), Offset(size.width, y(1.0)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            plot(close, span, ::y, CloseColor.copy(alpha = if (played == null) 1f else .35f), 1f)
            plot(rebound, span, ::y, ReboundColor.copy(alpha = if (played == null) 1f else .35f), 1f)
            played?.let { plot(it.curve, span, ::y, if (it.expand) ReboundColor else CloseColor, reveal, width = 3.dp.toPx(), dot = true) }
        }
        // Wraps to two lines at large font scales instead of truncating.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(CloseColor, "收起 ${close.durationMillis} ms", label)
            Legend(ReboundColor, "弹回 ${rebound.durationMillis} ms", label)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp, color = textColor, maxLines = 1)
    }
}

private fun DrawScope.plot(
    curve: SpringCurve,
    spanMs: Float,
    y: (Double) -> Float,
    color: Color,
    reveal: Float,
    width: Float = 2.dp.toPx(),
    dot: Boolean = false,
) {
    val path = Path()
    val steps = 90
    val end = (curve.durationMillis * reveal.coerceIn(0f, 1f))
    var last = Offset.Zero
    for (i in 0..steps) {
        val ms = end * i / steps
        val x = size.width * ms / spanMs
        val v = curve.valueAtSeconds(ms / 1000.0)
        last = Offset(x, y(v))
        if (i == 0) path.moveTo(last.x, last.y) else path.lineTo(last.x, last.y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
    if (dot && reveal > 0f) drawCircle(color, width * 1.6f, last)
}
