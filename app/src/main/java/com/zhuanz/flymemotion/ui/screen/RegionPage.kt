package com.zhuanz.flymemotion.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.zhuanz.flymemotion.core.BottomGestureRegion
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueSlider
import com.zhuanz.flymemotion.ui.preview.RegionPreview
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun RegionPage(vm: ManagerViewModel, blur: Boolean, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    // The draft moves the preview on every slider frame; the store is written once per drag.
    var draft by remember(config.revision) { mutableStateOf(config.bottomRegion) }
    var result by remember { mutableStateOf<String?>(null) }
    val save = { vm.saveRegion(draft) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val previewHeight = (windowHeight * .34f).coerceIn(200f, 300f).dp

    fun with(height: Float = draft.height(), width: Float = draft.width(), center: Float = draft.center()) =
        BottomGestureRegion(draft.enabled(), height, width, center)

    PageScaffold(
        title = "底部解锁区域",
        blur = blur,
        onBack = onBack,
        pinned = {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp)) {
                RegionPreview(
                    region = draft,
                    config = config,
                    modifier = Modifier.fillMaxWidth().height(previewHeight).padding(top = 12.dp),
                    onResult = { result = it },
                )
                AnimatedContent(result ?: "在上面的手机里按下并上滑，看看这一下归谁处理。",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "result") { text ->
                    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("region_result"))
                }
            }
        },
    ) {
        item(key = "switch") {
            Section(footer = "区域内按下的整段触摸用于解锁，快捷按钮、横滑和长按会被拦截。区域外保持原生。") {
                SwitchPreference(
                    title = "区域内优先解锁",
                    checked = draft.enabled(),
                    onCheckedChange = { on ->
                        draft = BottomGestureRegion(on, draft.height(), draft.width(), draft.center())
                        vm.saveRegion(draft)
                    },
                    modifier = Modifier.testTag("region_enable"),
                )
            }
        }
        item(key = "size") {
            Section(title = "范围", footer = "高度从屏幕底边算起。宽度为 100% 时不需要调整位置。") {
                ValueSlider(
                    title = "高度", tag = "slider_region_height", enabled = draft.enabled(),
                    value = draft.height(), range = .05f..0.4f, detent = .15f, valueText = "${(draft.height() * 100).roundToInt()}%",
                    onValueChange = { draft = with(height = pct(it)) }, onFinished = save,
                )
                ValueSlider(
                    title = "宽度", tag = "slider_region_width", enabled = draft.enabled(),
                    value = draft.width(), range = .2f..1f, detent = 1f, valueText = "${(draft.width() * 100).roundToInt()}%",
                    onValueChange = { draft = with(width = pct(it)) }, onFinished = save,
                )
                ValueSlider(
                    title = "水平位置", tag = "slider_region_center",
                    enabled = draft.enabled() && draft.width() < 1f,
                    value = draft.center(), range = 0f..1f, detent = .5f, valueText = "${(draft.center() * 100).roundToInt()}%",
                    onValueChange = { draft = with(center = pct(it)) }, onFinished = save,
                )
            }
        }
    }
}

private fun pct(v: Float) = (v * 100).roundToInt() / 100f
