package com.zhuanz.flymemotion.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.PanelCorner
import com.zhuanz.flymemotion.core.MotionConfig
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.MotionPreset
import com.zhuanz.flymemotion.ui.Route
import com.zhuanz.flymemotion.ui.StatusModel
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueRow
import com.zhuanz.flymemotion.ui.component.ValueSlider
import com.zhuanz.flymemotion.ui.component.toneText
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MotionPage(vm: ManagerViewModel, blur: Boolean, bottom: Dp, open: (Route) -> Unit) {
    val config by vm.config.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val preset = remember(config) { MotionPreset.of(config) }
    val desktop = remember(config, receipts) { StatusModel.desktopAnimation(config, receipts) }

    PageScaffold(title = "手感", blur = blur, bottomInset = bottom) {
        if (!config.enabled) item(key = "enable") {
            Section {
                ValueRow("锁屏手感已停用", "开启", tone = Tone.Warn, onClick = { vm.setEnabled(true) },
                    modifier = Modifier.testTag("motion_enable_row"))
            }
        }
        item(key = "preset") {
            Section(
                title = "预设",
                footer = preset?.summary ?: "预设会覆盖弹簧和松手判定，模糊强度保持不变。",
            ) {
                val tabs = MotionPreset.entries.map { it.label } + if (preset == null) listOf("自定义") else emptyList()
                TabRowWithContour(
                    tabs = tabs,
                    selectedTabIndex = preset?.ordinal ?: MotionPreset.entries.size,
                    onTabSelected = { i -> MotionPreset.entries.getOrNull(i)?.let(vm::applyPreset) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("preset_tabs"),
                )
            }
        }
        item(key = "pages") {
            Section {
                ValueRow(
                    title = "手感调校",
                    value = preset?.label ?: "自定义",
                    onClick = { open(Route.Tuning) },
                    modifier = Modifier.testTag("open_tuning"),
                )
                val r = config.bottomRegion
                ValueRow(
                    title = "底部解锁区域",
                    value = if (r.enabled) "底部 ${(r.height * 100).roundToInt()}%" else "关闭",
                    onClick = { open(Route.Region) },
                    modifier = Modifier.testTag("open_region"),
                )
            }
        }
        item(key = "desktop") {
            Section(title = "细节") {
                SwitchPreference(
                    title = "晚加载桌面动画",
                    summary = "解锁完成后图标再入场",
                    checked = config.lateDesktopAnimation,
                    onCheckedChange = vm::setLateDesktop,
                    modifier = Modifier.testTag("late_desktop"),
                    bottomAction = desktop?.let { line ->
                        {
                            Text(line.text, style = MiuixTheme.textStyles.footnote1, color = toneText(line.tone),
                                modifier = Modifier.padding(top = 6.dp).testTag("late_desktop_state"))
                        }
                    },
                )
            }
        }
        item(key = "corner") {
            // The manager's own display reports the same panel corners the SystemUI hook reads.
            val view = LocalView.current
            val screen = remember(view) { PanelCorner.screenDp(view).roundToInt().toFloat() }
            val custom = config.cornerRadiusDp >= 0
            var draft by remember(config.revision) {
                mutableFloatStateOf(if (custom) config.cornerRadiusDp else screen)
            }
            Section(footer = "圆角只作用于上滑卡片的底部两角。") {
                SwitchPreference(
                    title = "自定义底部圆角",
                    summary = if (custom) "手机屏幕约 ${screen.roundToInt()} dp" else "跟随手机屏幕",
                    checked = custom,
                    onCheckedChange = { on -> vm.setCornerRadius(if (on) screen else MotionConfig.FOLLOW_SCREEN) },
                    modifier = Modifier.testTag("corner_custom"),
                )
                if (custom) ValueSlider(
                    title = "底部圆角", tag = "slider_corner",
                    value = draft, range = 0f..MotionConfig.MAX_CORNER_DP, detent = screen.coerceIn(0f, MotionConfig.MAX_CORNER_DP),
                    valueText = "${draft.roundToInt()} dp",
                    onValueChange = { draft = it.roundToInt().toFloat() },
                    onFinished = { vm.setCornerRadius(draft) },
                )
            }
        }
    }
}

internal fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)
