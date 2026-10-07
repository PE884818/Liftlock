package com.zhuanz.flymemotion.ui.preview

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.core.FlymeMotion
import com.zhuanz.flymemotion.core.MotionConfig
import com.zhuanz.flymemotion.core.MotionFrame
import com.zhuanz.flymemotion.core.SpringCurve
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A release the preview just played, for the chart's moving dot and the verdict line. */
data class Release(val curve: SpringCurve, val expand: Boolean, val verdict: String)

/** Ratio of the phone's real height to the preview's, so a flick here is judged as a full-screen flick. */
@Composable
fun rememberScreenHeightPx(): Float {
    return rememberScreenGeometry().height
}

/**
 * Explains a decision with the same branches FlymeMotion.expand takes. [vyDp]/[vxDp] are already
 * scaled to full-screen dp/s; "expand" means the lock screen springs back.
 */
fun verdict(p: Float, vyDp: Float, vxDp: Float, c: MotionConfig, expand: Boolean): String {
    val swiped = ((1 - p) * 100).roundToInt()
    val need = ((1 - c.threshold) * 100).roundToInt()
    val speed = abs(vyDp).roundToInt()
    val min = c.minimumVelocityDp.roundToInt()
    val outcome = if (expand) "弹回" else "解锁"
    return when {
        abs(vyDp) < c.minimumVelocityDp -> "慢滑 $swiped%，" + (if (expand) "不到" else "达到") + " $need%，$outcome"
        vyDp >= 0 -> "向下甩，$speed dp/s，$outcome"
        abs(vyDp / hypot(vxDp, vyDp)) < .5f -> "横向甩动，$outcome"
        else -> "快速上滑，$speed dp/s，$outcome"
    }
}

/**
 * A simulated lock screen the user can swipe. Every number comes from motion-core: the pose from
 * MotionFrame.sample, the release from FlymeMotion.expand, the settle from SpringCurve, sampled the
 * way SystemUiHooks samples it. It is a picture of the motion, not the system lock screen.
 */
@Composable
fun LockPreview(
    config: MotionConfig,
    lateDesktop: Boolean,
    modifier: Modifier = Modifier,
    onRelease: (Release) -> Unit,
) {
    val density = LocalDensity.current.density
    val screenPx = rememberScreenHeightPx()
    val geometry = rememberScreenGeometry()
    val scope = rememberCoroutineScope()
    val currentConfig by rememberUpdatedState(config)
    val currentLate by rememberUpdatedState(lateDesktop)
    BoxWithConstraints(
        modifier
            .clip(ScreenShape(geometry))
            .testTag("lock_preview")
            .semantics { contentDescription = "锁屏手感模拟预览，可在其中上滑试划" },
    ) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        var raw by remember(heightPx) { mutableFloatStateOf(heightPx) }
        var velocity by remember { mutableFloatStateOf(0f) }
        val icons = remember { Animatable(0f) }
        var job by remember { mutableStateOf<Job?>(null) }

        fun enterIcons() {
            if (icons.value == 0f && !icons.isRunning) scope.launch {
                icons.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 260f))
            }
        }

        val frame = MotionFrame.sample(raw, velocity, heightPx, true, config.blurStrength)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInputLock(
                    key = heightPx,
                    onDown = {
                        job?.cancel(); job = null
                        if (raw <= 1f) { raw = heightPx; scope.launch { icons.snapTo(0f) } }
                    },
                    onDrag = { dy ->
                        raw = (raw + dy).coerceIn(0f, heightPx)
                        if (!currentLate && raw < heightPx * .97f) enterIcons()
                    },
                    onUp = { vx, vy ->
                        val scale = screenPx / heightPx
                        val p = FlymeMotion.progress(raw, heightPx)
                        val c = currentConfig
                        val expand = FlymeMotion.expand(p, vy * scale, vx * scale, density, c)
                        val start = raw
                        val end = if (expand) heightPx else 0f
                        val delta = end - start
                        val curve = SpringCurve(c.stiffness, if (expand) c.expandDamping else c.closeDamping,
                            if (abs(delta) > 1f) vy / delta else 0f, delta * scale)
                        onRelease(Release(curve, expand, verdict(p, vy * scale / density, vx * scale / density, c, expand)))
                        job = scope.launch {
                            if (abs(delta) > 1f) {
                                val t0 = withFrameNanos { it }
                                var elapsed = 0L
                                while (elapsed < curve.durationMillis) {
                                    elapsed = (withFrameNanos { it } - t0) / 1_000_000
                                    val f = (elapsed.toFloat() / curve.durationMillis).coerceAtMost(1f)
                                    raw = start + delta * curve.interpolate(f)
                                    velocity = delta * curve.velocityAtSeconds(f * curve.durationMillis / 1000.0).toFloat()
                                }
                            }
                            raw = end; velocity = 0f
                            if (!expand) {
                                if (currentLate) enterIcons()
                                delay(1100)
                                icons.snapTo(0f)
                                val back = Animatable(0f)
                                back.animateTo(heightPx, tween(380)) { raw = value }
                            } else if (icons.value > 0f) icons.animateTo(0f, tween(160))
                            job = null
                        }
                    },
                ),
        ) {
            Desktop(icons.value)
            // Lock wallpaper sheet: covers the top wallpaperHeight, fades near the end, blurs with progress.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frame.wallpaperAlpha() }
                    .drawWithContent { clipRect(bottom = frame.wallpaperHeight()) { this@drawWithContent.drawContent() } },
            ) {
                Box(Modifier.fillMaxSize().blur((frame.blurProgress() * 18).dp, BlurredEdgeTreatment.Rectangle).background(LockWallpaper))
            }
            LockContent(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, frame.contentY().roundToInt()) },
            )
        }
    }
}

private val LockWallpaper = Brush.verticalGradient(listOf(Color(0xFF2B3A67), Color(0xFF6B4C8A), Color(0xFFE08E6D)))
private val DesktopWallpaper = Brush.verticalGradient(listOf(Color(0xFF9ED2F5), Color(0xFFB9C7F2), Color(0xFFE7D9F4)))
private val IconColors = listOf(0xFF4F8DF7, 0xFF35C46A, 0xFFF6B23C, 0xFFEE5F5B, 0xFF8E6CF0, 0xFF2BB7C7, 0xFFF08BB4, 0xFF5A6B86)

@Composable
private fun Desktop(enter: Float) {
    Canvas(Modifier.fillMaxSize().background(DesktopWallpaper)) {
        val cols = 4
        val rows = 5
        val cell = size.width / cols
        val icon = cell * .58f
        val top = size.height * .14f
        val scale = .78f + .22f * enter
        for (r in 0 until rows) for (c in 0 until cols) {
            val cx = cell * (c + .5f)
            val cy = top + r * cell * 1.06f + icon / 2
            val s = icon * scale
            drawRoundRect(
                Color(IconColors[(r * cols + c) % IconColors.size]).copy(alpha = .45f + .55f * enter),
                topLeft = Offset(cx - s / 2, cy - s / 2), size = Size(s, s), cornerRadius = CornerRadius(s * .28f),
            )
        }
        // Dock
        val dockTop = size.height - cell * 1.15f
        for (c in 0 until cols) {
            val cx = cell * (c + .5f)
            val s = icon * scale
            drawRoundRect(Color.White.copy(alpha = .4f + .5f * enter), Offset(cx - s / 2, dockTop + cell * .2f), Size(s, s), CornerRadius(s * .28f))
        }
    }
}

@Composable
private fun LockContent(modifier: Modifier) {
    // A picture of a phone: its text keeps a fixed physical size whatever the system font scale.
    val d = LocalDensity.current
    fun fixed(dp: Float) = with(d) { dp.dp.toSp() }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("模拟", fontSize = fixed(8f), color = Color.White.copy(alpha = .6f))
            Spacer(Modifier.height(6.dp))
            Text("09:41", fontSize = fixed(30f), fontWeight = FontWeight.Light, color = Color.White)
            Text("10月5日 星期日", fontSize = fixed(9f), color = Color.White.copy(alpha = .85f))
            Spacer(Modifier.height(14.dp))
            repeat(2) {
                Box(
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(Color.White.copy(alpha = .22f), RoundedCornerShape(8.dp)),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .width(36.dp)
                .height(3.dp)
                .background(Color.White.copy(alpha = .8f), RoundedCornerShape(2.dp)),
        )
        Text("上滑解锁", fontSize = fixed(8f), color = Color.White.copy(alpha = .8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        Box(Modifier.align(Alignment.BottomStart).padding(10.dp).size(18.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(9.dp)))
        Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(18.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(9.dp)))
    }
}

/** Raw vertical drag with a velocity tracker; the preview owns every move it sees. */
private fun Modifier.pointerInputLock(
    key: Any,
    onDown: () -> Unit,
    onDrag: (Float) -> Unit,
    onUp: (vx: Float, vy: Float) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            onDown()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                // The release event can carry the last stretch of movement; apply it before judging.
                onDrag(change.positionChange().y)
                change.consume()
                if (!change.pressed) {
                    val v = tracker.calculateVelocity()
                    onUp(v.x, v.y)
                    break
                }
            }
        }
    },
)
