package com.zhuanz.flymemotion.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.RuntimeProvider
import com.zhuanz.flymemotion.RuntimeStatus
import com.zhuanz.flymemotion.TargetBuild
import com.zhuanz.flymemotion.core.MotionConfig
import com.zhuanz.flymemotion.ui.Link
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.Receipts
import com.zhuanz.flymemotion.ui.StatusModel
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.component.Footer
import com.zhuanz.flymemotion.ui.component.PageScaffold
import com.zhuanz.flymemotion.ui.component.Section
import com.zhuanz.flymemotion.ui.component.ValueRow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Every counter the old manager printed, grouped by process. Technical by design. */
fun describe(s: RuntimeStatus): String = buildString {
    appendLine("state=${s.state} fresh=${s.fresh} version=${s.moduleVersion.ifEmpty { "-" }} current=${s.currentModule()}")
    appendLine("hooks=${s.hooks} entry=${s.entry} configRevision=${s.configRevision} enabled=${s.enabled}")
    appendLine("touch=${s.touch} drag=${s.drag} height=${s.height} release=${s.release} intercept=${s.intercept}")
    appendLine("motion=${s.motion} blur=${s.blur} fling=${s.fling} spring=${s.spring} fastRedirect=${s.fastRedirect}")
    appendLine("coordinatedRelease=${s.coordinatedRelease} nativeRelease=${s.nativeRelease} scaleSuppressed=${s.scaleSuppressed}")
    appendLine("panel=${s.panelFound} keyguard=${s.keyguardFound} wallpaperBound=${s.wallpaperBound} wallpaperFrames=${s.wallpaperFrames}")
    appendLine("sceneSession=${s.sceneSession} surfaceGeneration=${s.surfaceGeneration} sceneWorkMax=${s.sceneWorkMaxMs}ms（含等待窗口锁，不是屏幕帧耗时）")
    appendLine("returnTarget=${s.returnTarget}")
    appendLine("desktopAnimation=${s.desktopAnimationState} ${s.desktopAnimationDetail}")
    appendLine("releaseReason=${s.releaseReason}")
    appendLine("motionFrame=${s.motionFrame}")
    if (s.gesture.isNotEmpty()) appendLine("gesture=${s.gesture}")
    if (s.scene.isNotEmpty()) appendLine("scene=${s.scene}")
    if (s.blocked.isNotEmpty()) appendLine("blocked=${s.blocked}")
    appendLine("wallpaper=${s.wallpaper}")
    appendLine("liveScene=${s.liveScene}")
    appendLine("sceneReadiness=${s.sceneReadiness}")
    appendLine("lastHomeReadiness=${s.lastHomeReadiness}")
    if (s.layers.isNotEmpty()) appendLine("layers=${s.layers}")
    if (s.error.isNotEmpty()) appendLine("error=${s.error}")
    if (s.compat.isNotEmpty()) {
        appendLine("compat:")
        s.compat.trimEnd().lines().forEach { appendLine("  $it") }
    }
}.trimEnd()

fun report(config: MotionConfig, link: Link, r: Receipts): String = buildString {
    appendLine("Liftlock ${TargetBuild.MODULE_VERSION} reference=${TargetBuild.referenceBuild()}")
    appendLine("device=${android.os.Build.DEVICE} incremental=${android.os.Build.VERSION.INCREMENTAL} sdk=${android.os.Build.VERSION.SDK_INT}")
    appendLine("config rev=${config.revision} enabled=${config.enabled} late=${config.lateDesktopAnimation} k=${config.stiffness} " +
        "expand=${config.expandDamping} close=${config.closeDamping} v=${config.minimumVelocityDp} t=${config.threshold} blur=${config.blurStrength} corner=${config.cornerRadiusDp} " +
        "region=${config.bottomRegion}")
    appendLine("lsposed connected=${link.connected} pending=${link.pending} problem=${link.problem} ${link.framework} scope=${link.scope}")
    r.readError?.let { appendLine(it) }
    StatusModel.processes.forEach { (pkg, name) ->
        appendLine(); appendLine("[$name $pkg]"); appendLine(describe(r.of(pkg)))
        val journal = r.raw?.getString("$pkg.gestureJournal").orEmpty()
        if (journal.isNotEmpty()) appendLine("gestureJournal=$journal")
    }
}

@Composable
fun DiagnosticsPage(vm: ManagerViewModel, blur: Boolean, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val link by vm.link.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val context = LocalContext.current
    PageScaffold(
        title = "诊断详情",
        blur = blur,
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Liftlock 诊断", report(config, link, receipts)))
                Toast.makeText(context, "已复制全部诊断", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.testTag("copy_diagnostics")) {
                Icon(MiuixIcons.Copy, contentDescription = "复制全部诊断", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
    ) {
        val compat = StatusModel.compat(receipts)
        if (compat.isNotEmpty()) item(key = "compat") {
            Section(title = "兼容性", footer = "只看这台手机上各部分能否接入，不判断机型。右上角复制的诊断里带完整兼容报告。") {
                compat.forEach { c ->
                    ValueRow(c.feature, if (c.on) "可用" else "未生效", tone = if (c.on) Tone.Good else Tone.Warn,
                        note = c.detail.ifEmpty { null }?.let { if (c.on) it else "${c.process}：$it" })
                }
            }
        }
        StatusModel.processes(link, receipts).forEach { row ->
            item(key = row.pkg) {
                Section(title = row.name) {
                    ValueRow("状态", row.level.label, tone = row.level.tone, note = row.note)
                    Text(
                        describe(receipts.of(row.pkg)),
                        style = MiuixTheme.textStyles.footnote2, fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
        item(key = "note") {
            Footer("回执来自 ${RuntimeProvider.URI}，只接受四个作用域进程写入，本次开机 150 秒内的记录才算新鲜。计数只说明回调发生过。")
        }
    }
}
