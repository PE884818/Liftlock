package com.zhuanz.flymemotion.ui.preview

import android.content.Context
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The real panel in portrait: its pixel size and the corner radii the system reports for the
 * display (top-left, top-right, bottom-right, bottom-left). Both previews draw from this.
 */
data class ScreenGeometry(val width: Float, val height: Float, val radii: FloatArray) {
    val aspect: Float get() = width / height
    /** The four radii scaled to a preview [previewWidth] wide. */
    fun scaledRadii(previewWidth: Float): FloatArray = FloatArray(4) { radii[it] * previewWidth / width }

    companion object {
        // getIdentifier: the framework's corner dimen is only a fallback when the display reports none.
        @android.annotation.SuppressLint("DiscouragedApi")
        fun read(context: Context): ScreenGeometry {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val w = min(bounds.width(), bounds.height()).toFloat()
            val h = maxOf(bounds.width(), bounds.height()).toFloat()
            val display = runCatching { context.display }.getOrNull()
            val positions = intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT)
            val reported = FloatArray(4) { i -> display?.getRoundedCorner(positions[i])?.radius?.toFloat() ?: 0f }
            // Some panels report nothing; the framework's own resource is the system's next answer.
            val fallback = context.resources.getIdentifier("rounded_corner_radius", "dimen", "android")
                .takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it).toFloat() } ?: 0f
            val radii = FloatArray(4) { reported[it].takeIf { r -> r > 0 } ?: fallback.takeIf { r -> r > 0 } ?: w * .09f }
            return ScreenGeometry(w, h, radii)
        }
    }

    override fun equals(other: Any?) = other is ScreenGeometry && width == other.width && height == other.height
            && radii.contentEquals(other.radii)
    override fun hashCode() = 31 * (31 * width.hashCode() + height.hashCode()) + radii.contentHashCode()
}

@Composable
fun rememberScreenGeometry(): ScreenGeometry {
    val context = LocalContext.current
    return remember(context) { ScreenGeometry.read(context) }
}

/** The display outline at preview scale, with G2 (continuous-curvature) corners. */
class ScreenShape(private val geometry: ScreenGeometry) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(continuousRect(Rect(Offset.Zero, size), geometry.scaledRadii(size.width)))
}

private const val SMOOTHING = .6f

/**
 * A rounded rectangle whose corners blend curvature smoothly into the edges (the "squircle"
 * construction: two cubic transitions around a shortened circular arc), [radii] clockwise from
 * top-left. Each corner keeps its circular radius at the apex, as the panel does.
 */
fun continuousRect(rect: Rect, radii: FloatArray): Path {
    val path = Path()
    val half = min(rect.width, rect.height) / 2
    // Corner order and the mapping from a top-right local frame (X back along the incoming
    // edge, Y along the outgoing one) to the real corner.
    val maps: List<(Float, Float) -> Offset> = listOf(
        { x, y -> Offset(rect.left + y, rect.top + x) },       // top-left
        { x, y -> Offset(rect.right - x, rect.top + y) },      // top-right
        { x, y -> Offset(rect.right - y, rect.bottom - x) },   // bottom-right
        { x, y -> Offset(rect.left + x, rect.bottom - y) },    // bottom-left
    )
    val order = intArrayOf(1, 2, 3, 0)
    var first = true
    for (i in order) {
        val map = maps[i]
        val r = min(radii[i], half)
        if (r <= 0f) {
            val p = map(0f, 0f)
            if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            first = false; continue
        }
        val s = min(SMOOTHING, half / r - 1).coerceAtLeast(0f)
        val p = (1 + s) * r
        val arcMeasure = 90f * (1 - s)
        val arcSection = sin(rad(arcMeasure / 2)) * r * sqrt(2f)
        val alpha = (90f - arcMeasure) / 2
        val p3ToP4 = r * tan(rad(alpha / 2))
        val beta = 45f * s
        val c = p3ToP4 * cos(rad(beta))
        val d = c * tan(rad(beta))
        val b = (p - arcSection - c - d) / 3
        val a = 2 * b
        val start = map(p, 0f)
        if (first) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
        first = false
        cubic(path, map(p - a, 0f), map(p - a - b, 0f), map(p - a - b - c, d))
        // The circular arc, centred (r, r) in local space, from -90+alpha to -alpha degrees.
        val t1 = rad(-90f + alpha); val t2 = rad(-alpha)
        val k = 4f / 3f * tan((t2 - t1) / 4) * r
        fun local(t: Float) = Pair(r - r * cos(t), r + r * sin(t))
        val (x1, y1) = local(t1); val (x2, y2) = local(t2)
        // Arc tangent in local space: d/dt (X, Y) = r (sin t, cos t).
        cubic(path,
            map(x1 + k * sin(t1), y1 + k * cos(t1)),
            map(x2 - k * sin(t2), y2 - k * cos(t2)),
            map(x2, y2))
        cubic(path, map(0f, p - a - b), map(0f, p - a), map(0f, p))
    }
    path.close()
    return path
}

private fun cubic(path: Path, c1: Offset, c2: Offset, end: Offset) = path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
private fun rad(deg: Float) = (deg * Math.PI / 180).toFloat()
