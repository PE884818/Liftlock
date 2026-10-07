package com.zhuanz.flymemotion.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanz.flymemotion.core.ReloadPlan
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.ReloadPhase
import com.zhuanz.flymemotion.ui.ReloadState
import com.zhuanz.flymemotion.ui.Route
import com.zhuanz.flymemotion.ui.UiSettings
import com.zhuanz.flymemotion.ui.Author
import com.zhuanz.flymemotion.ui.component.ConfirmDialog
import com.zhuanz.flymemotion.ui.component.Footer
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val reloadRows = listOf(
    ReloadPlan.SYSTEMUI to "重载系统界面",
    ReloadPlan.WALLPAPER to "重载壁纸",
    ReloadPlan.SETTINGS to "重载系统设置",
)

/** The short state at the end of a reload row; "已加载" appears only once the new process reported. */
fun reloadValue(state: ReloadState, pkg: String): String? = when (val phase = state.phases[pkg]) {
    ReloadPhase.Running -> null
    is ReloadPhase.Sent -> "等待回执"
    is ReloadPhase.Confirmed -> "已加载 ${phase.version.substringBefore('-')}"
    ReloadPhase.TimedOut -> "未收到回执"
    is ReloadPhase.Finished, null -> null
}

/** A sentence under the row only when the reload needs explaining (refused, failed, settings page). */
fun reloadNote(state: ReloadState, pkg: String): String? = when (val phase = state.phases[pkg]) {
    ReloadPhase.Running -> state.message.ifEmpty { null }
    is ReloadPhase.Sent -> if (pkg == ReloadPlan.SETTINGS) "重新打开系统的锁屏设置页后回报" else null
    ReloadPhase.TimedOut -> "150 秒内没有新回执，可以在诊断详情里查看"
    is ReloadPhase.Finished -> phase.message
    else -> null
}

private val themeModes = listOf("System" to "跟随系统", "Light" to "浅色", "Dark" to "深色", "MonetSystem" to "Monet 取色")

@Composable
fun MorePage(vm: ManagerViewModel, ui: UiSettings, bottom: Dp, open: (Route) -> Unit) {
    val reload by vm.reload.collectAsState()
    val related by vm.related.collectAsState()
    val context = LocalContext.current
    var pendingReload by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restoreMotion by remember { mutableStateOf(true) }
    var restoreRegion by remember { mutableStateOf(true) }
    var disabling by remember { mutableStateOf(false) }
    var uninstall by remember { mutableStateOf(false) }

    PageScaffold(title = "更多", blur = ui.blur, bottomInset = bottom) {
        item(key = "author") {
            AuthorCard(
                onGithub = { if (!Author.openGithub(context)) Toast.makeText(context, Author.GITHUB, Toast.LENGTH_LONG).show() },
                onDonate = { open(Route.Donate) },
            )
        }
        item(key = "look") {
            Section(title = "外观") {
                WindowDropdownPreference(
                    title = "主题",
                    items = themeModes.map { it.second },
                    selectedIndex = themeModes.indexOfFirst { it.first == ui.themeMode }.coerceAtLeast(0),
                    onSelectedIndexChange = { vm.updateUi(ui.copy(themeMode = themeModes[it].first)) },
                )
                SwitchPreference(
                    title = "悬浮导航栏",
                    checked = ui.floatingNav,
                    onCheckedChange = { vm.updateUi(ui.copy(floatingNav = it)) },
                )
                if (isRuntimeShaderSupported()) SwitchPreference(
                    title = "界面模糊",
                    checked = ui.blur,
                    onCheckedChange = { vm.updateUi(ui.copy(blur = it)) },
                )
            }
        }
        item(key = "process") {
            Section(title = "重新加载", footer = "需要 Root。系统框架的改动要重启手机才会加载。") {
                reloadRows.forEach { (pkg, title) ->
                    val running = reload.busy && reload.phases[pkg] == ReloadPhase.Running
                    BasicComponent(
                        title = title,
                        summary = reloadNote(reload, pkg),
                        enabled = !reload.busy || running,
                        endActions = {
                            if (running) InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary)
                            else reloadValue(reload, pkg)?.let {
                                Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                            }
                        },
                        onClick = { if (!reload.busy) pendingReload = pkg },
                        modifier = Modifier.testTag("reload_$pkg"),
                    )
                }
            }
        }
        item(key = "recover") {
            Section(title = "恢复") {
                ArrowPreference(
                    title = "恢复默认参数",
                    onClick = { restoreMotion = true; restoreRegion = true; restoring = true },
                    modifier = Modifier.testTag("restore_defaults"),
                )
                ArrowPreference(
                    title = "故障停用",
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    onClick = { disabling = true },
                    modifier = Modifier.testTag("emergency_disable"),
                )
                ArrowPreference(title = "ADB 卸载命令", onClick = { uninstall = true })
            }
        }
        item(key = "help") {
            Section(title = "帮助") {
                ArrowPreference(title = "首次启用", onClick = { open(Route.Guide) }, modifier = Modifier.testTag("open_guide"))
                ArrowPreference(title = "诊断详情", onClick = { open(Route.Diagnostics) }, modifier = Modifier.testTag("open_diagnostics"))
                ArrowPreference(title = "开源许可", onClick = { open(Route.Licenses) }, modifier = Modifier.testTag("open_licenses"))
            }
        }
        if (related.isNotEmpty()) item(key = "related") {
            Section(title = "可能冲突的模块", footer = "它们也会修改锁屏或系统界面。遇到异常时，先确认重叠的是哪项功能。") {
                related.forEach { BasicComponent(title = it) }
            }
        }
        item(key = "license") {
            Footer("Liftlock 开源免费，按 AGPL-3.0 发布。", Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
    }

    val target = pendingReload
    ConfirmDialog(
        show = target != null,
        title = reloadRows.firstOrNull { it.first == target }?.second ?: "",
        summary = when (target) {
            ReloadPlan.SYSTEMUI -> "锁屏、状态栏和导航会重启一次，几秒后恢复。"
            ReloadPlan.WALLPAPER -> "先撤销进行中的过渡，再让系统重新绑定壁纸，桌面壁纸可能闪一下。"
            else -> "系统设置会重启，重新打开锁屏设置页即可。"
        },
        confirm = "重载",
        onDismiss = { pendingReload = null },
        onConfirm = { target?.let(vm::reload) },
    )
    ConfirmDialog(
        show = restoring,
        title = "恢复默认参数",
        summary = "选择要恢复的内容。总开关和晚加载桌面动画不受影响。",
        confirm = "恢复",
        onDismiss = { restoring = false },
        onConfirm = { vm.restoreDefaults(restoreMotion, restoreRegion) },
    ) {
        CheckboxPreference(title = "手感参数", summary = "弹簧、松手判定和模糊强度", checked = restoreMotion,
            onCheckedChange = { restoreMotion = it }, insideMargin = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp))
        CheckboxPreference(title = "底部区域", summary = "开启，底部 15%，全宽", checked = restoreRegion,
            onCheckedChange = { restoreRegion = it }, insideMargin = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp))
    }
    ConfirmDialog(
        show = disabling,
        title = "故障停用",
        summary = "锁屏手势交回系统原生处理。其他设置都会保留，随时可以重新开启。",
        confirm = "停用",
        onDismiss = { disabling = false },
        onConfirm = vm::emergencyDisable,
    )
    val command = "adb uninstall com.zhuanz.flymemotion"
    ConfirmDialog(
        show = uninstall,
        title = "ADB 卸载命令",
        summary = "手机进不了系统时，在电脑上执行这条命令卸载模块，然后重启手机。",
        confirm = "复制命令",
        onDismiss = { uninstall = false },
        onConfirm = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("恢复命令", command))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        },
    ) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(command, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}
