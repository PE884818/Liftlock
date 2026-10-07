package com.zhuanz.flymemotion.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.MotionParams
import com.zhuanz.flymemotion.ui.component.ConfirmDialog
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueSlider
import com.zhuanz.flymemotion.ui.preview.LockPreview
import com.zhuanz.flymemotion.ui.preview.Release
import com.zhuanz.flymemotion.ui.preview.SpringChart
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun TuningPage(vm: ManagerViewModel, blur: Boolean, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    // Draft follows the store whenever a save (or a preset elsewhere) lands a new revision.
    var draft by remember(config.revision) { mutableStateOf(MotionParams.of(config)) }
    val live = remember(draft, config) { draft.preview(config) }
    var played by remember { mutableStateOf<Release?>(null) }
    val reveal = remember { Animatable(1f) }
    var resetting by remember { mutableStateOf(false) }
    val save = { vm.saveMotion(draft) }

    LaunchedEffect(played) {
        val p = played ?: return@LaunchedEffect
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(p.curve.durationMillis.toInt(), easing = LinearEasing))
    }

    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val previewHeight = (windowHeight * .30f).coerceIn(180f, 260f).dp
    val geometry = com.zhuanz.flymemotion.ui.preview.rememberScreenGeometry()

    PageScaffold(
        title = "手感调校",
        blur = blur,
        onBack = onBack,
        actions = {
            IconButton(onClick = { resetting = true }, modifier = Modifier.testTag("tuning_reset")) {
                Icon(MiuixIcons.Reset, contentDescription = "恢复原版参数", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
        pinned = {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().height(previewHeight).padding(16.dp)) {
                    LockPreview(
                        config = live,
                        lateDesktop = config.lateDesktopAnimation,
                        // The real panel's proportions; the corners come from the display itself.
                        modifier = Modifier.fillMaxHeight().aspectRatio(geometry.aspect),
                        onRelease = { played = it },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        SpringChart(
                            stiffness = live.stiffness,
                            expandDamping = live.expandDamping,
                            closeDamping = live.closeDamping,
                            played = played,
                            reveal = if (played == null) 1f else reveal.value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                AnimatedContent(played?.verdict ?: "在左边的锁屏上滑试试",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "verdict") { text ->
                    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp).testTag("verdict"))
                }
            }
        },
    ) {
        item(key = "spring") {
            Section(title = "弹簧", footer = "刚度越大越快越硬。阻尼小于 1 时会轻微过冲。") {
                ValueSlider(
                    title = "刚度", tag = "slider_stiffness",
                    value = draft.stiffness, range = 50f..800f, detent = 200f, valueText = "${draft.stiffness.roundToInt()}",
                    onValueChange = { draft = draft.copy(stiffness = it.roundToInt().toFloat()) }, onFinished = save,
                )
                ValueSlider(
                    title = "弹回阻尼", tag = "slider_expand",
                    value = draft.expandDamping, range = .3f..1.5f, detent = .81f, valueText = fmt(draft.expandDamping),
                    onValueChange = { draft = draft.copy(expandDamping = step(it, .01f)) }, onFinished = save,
                )
                ValueSlider(
                    title = "收起阻尼", tag = "slider_close",
                    value = draft.closeDamping, range = .3f..1.5f, detent = .95f, valueText = fmt(draft.closeDamping),
                    onValueChange = { draft = draft.copy(closeDamping = step(it, .01f)) }, onFinished = save,
                )
            }
        }
        item(key = "release") {
            Section(title = "松手判定", footer = "松手速度超过阈值时按甩动方向判定，否则看滑过的距离。") {
                ValueSlider(
                    title = "甩动速度", tag = "slider_velocity",
                    value = draft.minimumVelocityDp, range = 50f..2000f, detent = 250f, valueText = "${draft.minimumVelocityDp.roundToInt()} dp/s",
                    onValueChange = { draft = draft.copy(minimumVelocityDp = step(it, 10f)) }, onFinished = save,
                )
                // Shown as the swipe needed; stored as MotionConfig.threshold = 1 - distance.
                val distance = 1f - draft.threshold
                ValueSlider(
                    title = "慢滑解锁距离", tag = "slider_threshold",
                    value = distance, range = .1f..0.9f, detent = .5f, valueText = "${(distance * 100).roundToInt()}%",
                    onValueChange = { draft = draft.copy(threshold = step(1f - it, .01f)) }, onFinished = save,
                )
            }
        }
        item(key = "blur") {
            Section(title = "模糊") {
                ValueSlider(
                    title = "原生模糊强度", tag = "slider_blur",
                    value = draft.blurStrength, range = 0f..1f, detent = 1f, valueText = "${(draft.blurStrength * 100).roundToInt()}%",
                    onValueChange = { draft = draft.copy(blurStrength = step(it, .01f)) }, onFinished = save,
                )
            }
        }
    }

    ConfirmDialog(
        show = resetting,
        title = "恢复原版参数",
        summary = "弹簧、松手判定和模糊强度回到原版数值，底部区域和开关不变。",
        confirm = "恢复",
        onDismiss = { resetting = false },
        onConfirm = { vm.restoreDefaults(motion = true, region = false) },
    )
}

private fun step(v: Float, unit: Float) = (v / unit).roundToInt() * unit
