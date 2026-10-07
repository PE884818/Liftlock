package com.zhuanz.flymemotion.ui.preview

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.core.BottomGestureRegion
import com.zhuanz.flymemotion.core.FlymeMotion
import com.zhuanz.flymemotion.core.MotionConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The real phone's aspect with the region drawn from BottomGestureRegion.left/right/height. A
 * try-swipe is judged by BottomGestureRegion.contains at the press point, exactly as the SystemUI
 * adapter judges a real press; no system overlay or input grant is involved.
 */
@Composable
fun RegionPreview(
    region: BottomGestureRegion,
    config: MotionConfig,
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val geometry = rememberScreenGeometry()
    val aspect = geometry.aspect
    val accent = MiuixTheme.colorScheme.primary
    val outline = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val currentRegion by rememberUpdatedState(region)
    val currentConfig by rememberUpdatedState(config)
    val scope = rememberCoroutineScope()

    val spec = spring<Float>(dampingRatio = .82f, stiffness = 420f)
    val left by animateFloatAsState(region.left(), spec, label = "left")
    val right by animateFloatAsState(region.right(), spec, label = "right")
    val height by animateFloatAsState(region.height(), spec, label = "height")
    val shown by animateFloatAsState(if (region.enabled()) 1f else 0f, tween(220), label = "shown")

    val trace = remember { mutableStateListOf<Offset>() }
    var reserved by remember { mutableStateOf(false) }
    val traceAlpha = remember { Animatable(0f) }
    val measurer = rememberTextMeasurer()
    // Fixed physical text size inside the phone picture, independent of the system font scale.
    val clockSize = with(LocalDensity.current) { 22.dp.toSp() }
    val labelSize = with(LocalDensity.current) { 11.dp.toSp() }

    Canvas(
        modifier
            .testTag("region_preview")
            .semantics { contentDescription = "底部解锁区域实时预览，可在其中试划" }
            .pointerInput(aspect) {
                awaitEachGesture {
                    val screen = phoneRect(size.width.toFloat(), size.height.toFloat(), aspect)
                    val down = awaitFirstDown()
                    down.consume()
                    trace.clear(); trace += down.position
                    scope.launch { traceAlpha.snapTo(1f) }
                    reserved = currentRegion.contains(down.position.x - screen.left, down.position.y - screen.top, screen.width, screen.height)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        trace += change.position
                        change.consume()
                        if (!change.pressed) {
                            val v = tracker.calculateVelocity()
                            val scale = geometry.height / screen.height
                            val dy = change.position.y - down.position.y
                            val p = FlymeMotion.clamp(1f + dy / screen.height, 0f, 1f)
                            onResult(
                                if (!reserved) "区域外按下：按系统原样处理，快捷按钮和通知照常响应。"
                                else {
                                    val expand = FlymeMotion.expand(p, v.y * scale, v.x * scale, density, currentConfig)
                                    "区域内按下：交给解锁手势。" + verdict(p, v.y * scale / density, v.x * scale / density, currentConfig, expand) + "。"
                                },
                            )
                            scope.launch { traceAlpha.animateTo(0f, tween(900, delayMillis = 500)) }
                            break
                        }
                    }
                }
            },
    ) {
        val screen = phoneRect(size.width, size.height, aspect)
        val shape = continuousRect(screen, geometry.scaledRadii(screen.width))
        clipPath(shape) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3A67), Color(0xFF6B4C8A), Color(0xFFE08E6D)), screen.top, screen.bottom),
                screen.topLeft, screen.size)
            // Clock, notification pills and the two bottom shortcuts the region can overlap.
            val clock = measurer.measure("09:41", TextStyle(color = Color.White, fontSize = clockSize))
            drawText(clock, topLeft = Offset(screen.center.x - clock.size.width / 2f, screen.top + screen.height * .09f))
            for (i in 0..1) drawRoundRect(Color.White.copy(alpha = .22f),
                Offset(screen.left + screen.width * .08f, screen.top + screen.height * (.3f + i * .085f)),
                Size(screen.width * .84f, screen.height * .07f), CornerRadius(screen.width * .05f))
            val r = screen.width * .07f
            drawCircle(Color.White.copy(alpha = .3f), r, Offset(screen.left + screen.width * .15f, screen.bottom - screen.height * .07f))
            drawCircle(Color.White.copy(alpha = .3f), r, Offset(screen.right - screen.width * .15f, screen.bottom - screen.height * .07f))
            drawRoundRect(Color.White.copy(alpha = .85f), Offset(screen.center.x - screen.width * .12f, screen.bottom - screen.height * .025f),
                Size(screen.width * .24f, 3.dp.toPx()), CornerRadius(2.dp.toPx()))

            val hit = Rect(screen.left + screen.width * left, screen.bottom - screen.height * height,
                screen.left + screen.width * right, screen.bottom)
            drawRect(accent.copy(alpha = .38f * shown + .06f), hit.topLeft, hit.size)
            drawRect(accent.copy(alpha = .4f + .6f * shown), hit.topLeft, hit.size, style = Stroke(2.dp.toPx()))
            val label = measurer.measure(
                if (region.enabled()) "解锁区域" else "已关闭",
                TextStyle(color = Color.White, fontSize = labelSize))
            if (hit.height > label.size.height + 4) drawText(label,
                topLeft = Offset(hit.center.x - label.size.width / 2f, hit.top + 4.dp.toPx()))
        }
        drawPath(shape, outline.copy(alpha = .5f), style = Stroke(1.5.dp.toPx()))

        if (trace.size > 1 && traceAlpha.value > 0f) {
            val path = Path().apply { moveTo(trace[0].x, trace[0].y); trace.drop(1).forEach { lineTo(it.x, it.y) } }
            val color = (if (reserved) accent else Color.White).copy(alpha = traceAlpha.value)
            drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, 7.dp.toPx(), trace.last())
        }
    }
}

private fun phoneRect(w: Float, h: Float, aspect: Float): Rect {
    val ph = h - 8f
    val pw = minOf(w - 8f, ph * aspect)
    return Rect((w - pw) / 2, 4f, (w + pw) / 2, 4f + ph)
}
