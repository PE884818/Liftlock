package com.zhuanz.flymemotion.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

/**
 * Clawd, pixel for pixel the block-character mascot Claude Code prints at start-up
 * ("▐▛███▜▌ / ▝▜█████▛▘ / ▘▘ ▝▝", each character a 2×2 quadrant), here typing on a laptop.
 * Scene grid is 24×16 cells; Clawd's own 18×5 grid sits at (3, 5).
 */
@Composable
fun ClawdTyping(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "clawd")
    // Keystrokes: one arm, then the other, about three per second.
    val keys by motion.animateFloat(0f, 2f, infiniteRepeatable(tween(640, easing = LinearEasing)), label = "keys")
    // A blink near the end of every 3.4 s.
    val life by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "life")
    // Code drifting up off the screen.
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "drift")
    val glow by motion.animateFloat(.55f, .85f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Canvas(modifier) { drawClawdScene(keys, life, drift, glow) }
}

private val Body = Color(0xFFD97757)
private val Eye = Color(0xFF1B1B1B)
private val Lid = Color(0xFF3B3B40)
private val LidEdge = Color(0xFF55555C)
private val Deck = Color(0xFF9A9AA3)
private val Screen = Color(0xFF9CC9FF)

/** Clawd's cells as (column, row) on its 18×5 grid, arms excluded. */
private val clawdBody: List<Pair<Int, Int>> = buildList {
    for (c in 3..14) add(c to 0)
    for (c in 3..14) if (c != 5 && c != 12) add(c to 1)
    for (c in 3..14) add(c to 2)
    for (c in 3..14) add(c to 3)
    for (c in listOf(4, 6, 11, 13)) add(c to 4)
}

private fun DrawScope.drawClawdScene(keys: Float, life: Float, drift: Float, glow: Float) {
    val cell = min(size.width / 24f, size.height / 16f)
    val ox = (size.width - cell * 24f) / 2f
    val oy = (size.height - cell * 16f) / 2f
    fun px(c: Float, r: Float, color: Color, w: Float = 1f, h: Float = 1f, alpha: Float = 1f) =
        drawRect(color, Offset(ox + c * cell, oy + r * cell), Size(w * cell, h * cell), alpha = alpha)

    val cx = 3f
    val cy = 5f
    // Screen light on Clawd's face, behind everything.
    drawRect(
        Brush.radialGradient(listOf(Screen.copy(alpha = .28f * glow), Color.Transparent),
            center = Offset(ox + 12f * cell, oy + 9f * cell), radius = cell * 9f),
        Offset(ox, oy), Size(cell * 24f, cell * 16f),
    )
    clawdBody.forEach { (c, r) -> px(cx + c, cy + r, Body) }
    // Arms: the row-2 stubs, dropping a cell in turn onto the keys.
    val leftDown = keys < 1f
    px(cx + 1f, cy + 2f + if (leftDown) 1f else 0f, Body, w = 2f)
    px(cx + 15f, cy + 2f + if (!leftDown) 1f else 0f, Body, w = 2f)
    // Eyes, squeezed shut for a blink.
    val open = if (life > .93f) .25f else 1f
    for (c in listOf(5f, 12f)) {
        px(cx + c, cy + 1f, Body)
        px(cx + c, cy + 1f + (1f - open) * .5f, Eye, h = open)
    }
    // The laptop's lid seen from behind, covering Clawd's lower half, then the deck.
    px(5f, 9f, Lid, w = 14f, h = 4f)
    px(5f, 9f, LidEdge, w = 14f, h = .35f)
    px(11.5f, 10.5f, Color.White, w = 1f, h = 1f, alpha = .55f + .3f * glow)
    px(3f, 13f, Deck, w = 18f, h = 1f)
    px(3f, 14f, Deck, w = 18f, h = .4f, alpha = .5f)
    // Three bits of code floating up over Clawd's head, staggered.
    for (i in 0 until 3) {
        val t = (drift + i / 3f) % 1f
        val col = 8f + i * 3.5f + (i % 2) * .5f
        px(col, 4.2f - t * 4f, if (i == 1) Body else Screen, w = .7f, h = .7f, alpha = (1f - t) * .9f)
    }
}
