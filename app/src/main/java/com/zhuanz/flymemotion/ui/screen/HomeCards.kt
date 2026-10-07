package com.zhuanz.flymemotion.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.ui.Headline
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.component.Glyphs
import com.zhuanz.flymemotion.ui.theme.ToneColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * The miuix home row (KernelSU-miuix style): a tinted status card on the left whose glyph is
 * drawn large and bleeds off its corner, and two small fact cards stacked on the right. The
 * status card carries only the verdict; tapping it re-reads the receipts.
 */
@Composable
fun StatusRow(
    headline: Headline,
    enabled: Boolean,
    tuning: String,
    region: String,
    onRefresh: () -> Unit,
    onTuning: () -> Unit,
    onRegion: () -> Unit,
) {
    // A stopped module is a quiet state, whatever else the receipts say.
    val tone = if (!enabled && headline.tone != Tone.Bad && headline.tone != Tone.Warn) Tone.Idle else headline.tone
    val container by animateColorAsState(ToneColors.container(tone), spring(stiffness = 300f), label = "statusContainer")
    val accent by animateColorAsState(ToneColors.accent(tone), spring(stiffness = 300f), label = "statusAccent")
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 160.dp).testTag("status_card"),
            colors = CardDefaults.defaultColors(color = container),
            onClick = onRefresh,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(Modifier.fillMaxSize()) {
                Crossfade(tone, label = "glyph", modifier = Modifier.align(Alignment.BottomEnd).offset(x = 38.dp, y = 42.dp)) { t ->
                    Icon(Glyphs.of(t), contentDescription = null, tint = accent, modifier = Modifier.size(150.dp))
                }
                Column(Modifier.padding(16.dp)) {
                    AnimatedContent(headline.title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { title ->
                        Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("status_title"))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(headline.detail, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 28.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FactCard("手感", tuning, Modifier.weight(1f).fillMaxWidth().testTag("home_tuning"), onTuning)
            FactCard("底部解锁区域", region, Modifier.weight(1f).fillMaxWidth().testTag("home_region"), onRegion)
        }
    }
}

@Composable
private fun FactCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick, showIndication = true, pressFeedbackType = PressFeedbackType.Sink) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The donation entry, under the status row where it is seen without hunting. It holds still:
 * a light crosses the little payment cards once when the card appears and again on each press.
 */
@Composable
fun DonateBanner(onClick: () -> Unit) {
    val sheen = remember { Animatable(-.6f) }
    var presses by remember { mutableIntStateOf(0) }
    LaunchedEffect(presses) {
        sheen.snapTo(-.6f)
        sheen.animateTo(1.6f, tween(if (presses > 0) 700 else 1100, delayMillis = if (presses > 0) 0 else 500, easing = FastOutSlowInEasing))
    }
    val warm = Color(0xFFFF8A3D)
    Card(
        modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp).testTag("open_donate"),
        onClick = onClick, showIndication = true, pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); presses++ } }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(width = 76.dp, height = 72.dp)) {
                miniPayCard(Color(0xFF2B7BFF), Color(0xFF0A2F8F), -14f, -size.width * .14f, null)
                miniPayCard(Color(0xFF17B05E), Color(0xFF04522F), 10f, size.width * .12f, sheen.value)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("请作者喝杯咖啡", style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("几毛也是鼓励，让 Liftlock 继续打磨下去", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(10.dp))
                Text(
                    "去赞赏",
                    color = Color.White, fontWeight = FontWeight.SemiBold, style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(warm, Color(0xFFFF5E62))))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** A tiny payment card with a code on it; [sheen] (0..1 across, null for none) is a passing light. */
private fun DrawScope.miniPayCard(color: Color, deep: Color, degrees: Float, dx: Float, sheen: Float?) {
    val w = size.width * .62f
    val h = size.height * .86f
    val left = size.width / 2f - w / 2f + dx
    val top = (size.height - h) / 2f
    rotate(degrees, Offset(left + w / 2f, top + h / 2f)) {
        clipPath(Path().apply { addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(w * .16f))) }) {
            drawRect(Brush.linearGradient(listOf(color, deep), Offset(left + w, top), Offset(left, top + h)), Offset(left, top), Size(w, h))
            drawRoundRect(Color.White, Offset(left + w * .18f, top + h * .2f), Size(w * .64f, w * .64f), CornerRadius(w * .08f))
            for (i in 0 until 3) for (j in 0 until 3) if ((i + j) % 2 == 0) drawRect(
                deep.copy(alpha = .8f), Offset(left + w * (.26f + i * .17f), top + h * .2f + w * (.08f + j * .17f)), Size(w * .12f, w * .12f))
            drawRoundRect(Color.White.copy(alpha = .7f), Offset(left + w * .3f, top + h * .82f), Size(w * .4f, h * .05f), CornerRadius(h * .025f))
            if (sheen != null) {
                val x = left + w * sheen
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent),
                    Offset(x - w * .3f, top), Offset(x + w * .3f, top + h * .4f)), Offset(left, top), Size(w, h))
            }
        }
    }
}
