package com.zhuanz.flymemotion.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.core.ReloadPlan
import com.zhuanz.flymemotion.ui.Headline
import com.zhuanz.flymemotion.ui.HomeAction
import com.zhuanz.flymemotion.ui.Level
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.MotionPreset
import com.zhuanz.flymemotion.ui.Route
import com.zhuanz.flymemotion.ui.StatusModel
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.component.ConfirmDialog
import com.zhuanz.flymemotion.ui.component.Glyphs
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueRow
import com.zhuanz.flymemotion.ui.theme.Brand
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.roundToInt

@Composable
fun HomePage(vm: ManagerViewModel, blur: Boolean, bottom: Dp, open: (Route) -> Unit) {
    val config by vm.config.collectAsState()
    val link by vm.link.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val reload by vm.reload.collectAsState()
    val scope = rememberCoroutineScope()
    val headline = remember(config, link, receipts) { StatusModel.headline(config, link, receipts) }
    val rows = remember(link, receipts) { StatusModel.processes(link, receipts) }
    val problems = remember(config, link, receipts) { StatusModel.connection(config, link, receipts).filter { it.tone == Tone.Warn || it.tone == Tone.Bad } }
    var confirm by remember { mutableStateOf<HomeAction?>(null) }

    PageScaffold(title = "Liftlock", blur = blur, bottomInset = bottom) {
        item(key = "status") {
            val preset = MotionPreset.of(config)
            val r = config.bottomRegion
            StatusRow(
                headline, config.enabled,
                tuning = preset?.label ?: "自定义",
                region = if (r.enabled) "底部 ${(r.height * 100).roundToInt()}%" else "关闭",
                onRefresh = { scope.launch { vm.refreshReceipts() } },
                onTuning = { open(Route.Tuning) },
                onRegion = { open(Route.Region) },
            )
        }
        item(key = "switch") {
            Section {
                SwitchPreference(
                    title = "Flyme 锁屏手感",
                    summary = if (config.enabled) "上滑解锁使用 Flyme 的运动" else "使用系统原生手势",
                    checked = config.enabled,
                    onCheckedChange = vm::setEnabled,
                    modifier = Modifier.testTag("motion_enable"),
                )
            }
        }
        // The switch already turns the module on; the other actions get one row.
        headline.action?.takeIf { it != HomeAction.Enable }?.let { action ->
            item(key = "action") {
                Section {
                    ValueRow(
                        title = headline.actionLabel,
                        value = if (action == HomeAction.ReloadSystemUi) reloadValue(reload, ReloadPlan.SYSTEMUI) else null,
                        enabled = !(action == HomeAction.ReloadSystemUi && reload.busy),
                        modifier = Modifier.testTag("home_action"),
                        onClick = { if (action == HomeAction.OpenGuide) open(Route.Guide) else confirm = action },
                    )
                }
            }
        }
        item(key = "support") {
            DonateBanner { open(Route.Donate) }
        }
        item(key = "processes") {
            Section(title = "运行状态") {
                val loaded = rows.count { it.level == Level.Loaded || it.level == Level.Active || it.level == Level.Partial }
                val waiting = rows.all { it.level == Level.Unknown }
                ValueRow(
                    title = "系统组件",
                    value = when {
                        waiting -> "等待连接"
                        loaded == rows.size -> "全部已加载"
                        else -> "$loaded/${rows.size} 已加载"
                    },
                    tone = if (!waiting && loaded < rows.size) Tone.Warn else Tone.Idle,
                    onClick = { open(Route.Diagnostics) },
                    modifier = Modifier.testTag(if (waiting) "process_collapsed" else "process_summary"),
                )
                // Only the parts that ask something of the user get their own row.
                if (!waiting) rows.filter { it.note != null }.forEach { row ->
                    ValueRow(row.name, row.level.label, tone = row.level.tone, note = row.note,
                        onClick = { open(Route.Diagnostics) }, modifier = Modifier.testTag("process_${row.pkg}"))
                }
                problems.forEach { ValueRow(it.title, it.value, tone = it.tone, note = it.note) }
            }
        }
    }

    ConfirmDialog(
        show = confirm == HomeAction.ReloadSystemUi,
        title = "重载系统界面",
        summary = "锁屏、状态栏和导航会重启一次，几秒后恢复。需要 Root。",
        confirm = "重载",
        onDismiss = { confirm = null },
        onConfirm = { vm.reload(ReloadPlan.SYSTEMUI) },
    )
    ConfirmDialog(
        show = confirm == HomeAction.Disable,
        title = "停用 Liftlock",
        summary = "锁屏手势交回系统原生处理，其他设置都会保留。",
        confirm = "停用",
        onDismiss = { confirm = null },
        onConfirm = vm::emergencyDisable,
    )
}
