package com.zhuanz.flymemotion.ui

import com.zhuanz.flymemotion.core.CompatLog
import android.os.Bundle
import com.zhuanz.flymemotion.RuntimeStatus
import com.zhuanz.flymemotion.TargetBuild
import com.zhuanz.flymemotion.core.MotionConfig
import com.zhuanz.flymemotion.core.ReloadPlan

/**
 * Turns receipts into words. Pure: the same inputs always give the same text, and nothing here
 * can say more than a receipt proves. Wording ladder used everywhere: 已开启/已保存 → 已同步 →
 * 已加载 → 已回调/已触发. Nothing in this file claims "生效" or "验收".
 */
enum class Tone { Good, Idle, Warn, Bad }

const val FRAMEWORK = "android"
private const val FRAMEWORK_SCOPE = "system"

data class Link(
    val connected: Boolean = false,
    val pending: Boolean = false,
    val problem: String = "",
    val framework: String = "",
    val scope: List<String> = emptyList(),
) {
    fun hasScope(pkg: String): Boolean = if (pkg == FRAMEWORK)
        scope.contains(FRAMEWORK_SCOPE) || scope.contains(FRAMEWORK) else scope.contains(pkg)
}

data class Receipts(
    val ui: RuntimeStatus,
    val framework: RuntimeStatus,
    val wallpaper: RuntimeStatus,
    val settings: RuntimeStatus,
    val readError: String? = null,
    val raw: Bundle? = null,
) {
    fun of(pkg: String): RuntimeStatus = when (pkg) {
        ReloadPlan.SYSTEMUI -> ui
        FRAMEWORK -> framework
        ReloadPlan.WALLPAPER -> wallpaper
        else -> settings
    }

    companion object {
        fun read(bundle: Bundle?, now: Long, boot: Int, error: String? = null) = Receipts(
            RuntimeStatus.read(bundle, ReloadPlan.SYSTEMUI, now, boot),
            RuntimeStatus.read(bundle, FRAMEWORK, now, boot),
            RuntimeStatus.read(bundle, ReloadPlan.WALLPAPER, now, boot),
            RuntimeStatus.read(bundle, ReloadPlan.SETTINGS, now, boot),
            error, bundle,
        )
        val Empty = read(null, 0, -1)
    }
}

enum class HomeAction { ReloadSystemUi, OpenGuide, Disable, Enable }

data class Headline(val tone: Tone, val title: String, val detail: String, val action: HomeAction? = null, val actionLabel: String = "")

enum class Level(val label: String, val tone: Tone) {
    Unknown("未知", Tone.Idle),
    Unselected("未勾选", Tone.Warn),
    Waiting("等待回执", Tone.Idle),
    Outdated("旧版", Tone.Warn),
    Error("出错", Tone.Bad),
    Partial("部分可用", Tone.Warn),
    Loaded("已加载", Tone.Good),
    Active("已回调", Tone.Good),
}

/** One process: a short state word, plus [note] only when the user has something to do. */
data class ProcessRow(val pkg: String, val name: String, val level: Level, val note: String?)

data class LineStatus(val tone: Tone, val text: String)

/** One adapter part of one process on this system build, from the receipt's compat list. */
data class CompatRow(val pkg: String, val process: String, val feature: String, val on: Boolean, val detail: String)

/** A title and its value, for a settings-style row. */
data class Fact(val title: String, val value: String, val tone: Tone, val note: String? = null)

object StatusModel {
    val processes = listOf(
        ReloadPlan.SYSTEMUI to "系统界面",
        FRAMEWORK to "系统框架",
        ReloadPlan.WALLPAPER to "壁纸",
        ReloadPlan.SETTINGS to "系统设置",
    )

    fun headline(config: MotionConfig, link: Link, r: Receipts): Headline {
        val ui = r.ui
        val server = r.framework
        return when {
            r.readError != null -> Headline(Tone.Bad, "无法读取运行记录", r.readError)
            // A failure reported by an older loaded build (e.g. its removed device check) is not
            // this version's verdict: the processes simply still run the old code until restart.
            processes.map { r.of(it.first) }.any { staleFailure(it) } -> Headline(Tone.Warn, "需要重启手机",
                "系统进程还在运行旧版 ${processes.map { r.of(it.first) }.first { staleFailure(it) }.moduleVersion.ifEmpty { "模块" }}，"
                    + "报错来自旧版。重启后加载新版。")
            ui.fresh && ui.currentModule() && (ui.state == RuntimeStatus.State.FAILED || ui.state == RuntimeStatus.State.UNSUPPORTED) ->
                Headline(Tone.Bad, "已交回原生处理", ui.error.ifEmpty { "系统界面报告了兼容问题。" },
                    HomeAction.Disable, "停用 Liftlock")
            ui.fresh && ui.state == RuntimeStatus.State.LOADED && !ui.currentModule() -> Headline(Tone.Warn,
                "系统界面需要重载",
                "正在运行的还是 ${ui.moduleVersion.ifEmpty { "旧版" }}，重载后换成新版。",
                HomeAction.ReloadSystemUi, "重载系统界面")
            !config.enabled -> Headline(Tone.Idle, "已停用", when {
                ui.currentModule() && !ui.enabled && ui.configRevision >= config.revision -> "锁屏使用系统原生手势。"
                link.connected && link.problem.isEmpty() -> "停用已同步，等待系统界面确认。"
                else -> "停用已保存，LSPosed 连接后同步。"
            }, HomeAction.Enable, "开启")
            server.fresh && server.moduleVersion.isNotEmpty() && !server.currentModule() -> Headline(Tone.Warn,
                "需要重启手机", "系统框架还在运行 ${server.moduleVersion}，重启后加载新版。")
            ui.currentModule() && compat(r).any { !it.on } -> Headline(Tone.Warn, "部分可用",
                compat(r).filter { !it.on }.joinToString("、") { it.feature } + " 在这个系统上未生效，其余照常。")
            ui.currentModule() && ui.motion > 0 -> Headline(Tone.Good,
                if (ui.wallpaperBound) "锁屏手感已接入" else "已接入",
                if (ui.wallpaperBound) "最近一次上滑由 Liftlock 处理。" else "最近一次上滑由 Liftlock 处理，壁纸暂未绑定。")
            ui.currentModule() -> Headline(Tone.Good, "已就绪",
                if (ui.touch > 0) "收到了触摸，还没走完一次上滑。锁屏后上滑试试。"
                else "锁屏后上滑一次，这里会确认接入。")
            !link.connected -> Headline(Tone.Idle, "等待 LSPosed",
                "在 LSPosed 中启用 Liftlock。", HomeAction.OpenGuide, "查看启用步骤")
            processes.any { !link.hasScope(it.first) } -> Headline(Tone.Warn, "作用域不完整",
                "四个作用域都需要勾选。", HomeAction.OpenGuide, "查看启用步骤")
            else -> Headline(Tone.Idle, "等待系统界面加载",
                "作用域已勾选，本次开机还没加载。重载系统界面或重启手机后再看。",
                HomeAction.ReloadSystemUi, "重载系统界面")
        }
    }

    fun process(pkg: String, name: String, link: Link, s: RuntimeStatus): ProcessRow {
        val failed = s.fresh && (s.state == RuntimeStatus.State.FAILED || s.state == RuntimeStatus.State.UNSUPPORTED)
        val level = when {
            staleFailure(s) -> Level.Outdated
            failed -> Level.Error
            s.fresh && s.moduleVersion.isNotEmpty() && !s.currentModule() -> Level.Outdated
            s.currentModule() && offFeatures(s).isNotEmpty() -> Level.Partial
            s.currentModule() && callbacks(pkg, s) > 0 -> Level.Active
            s.currentModule() -> Level.Loaded
            link.connected && !link.hasScope(pkg) -> Level.Unselected
            link.connected -> Level.Waiting
            else -> Level.Unknown
        }
        val note = when (level) {
            Level.Error -> s.error.ifEmpty { "报告了兼容问题，已保持原生行为" }
            Level.Outdated -> "运行的是 ${s.moduleVersion.ifEmpty { "旧版" }}" + (if (staleFailure(s)) "，报错来自旧版" else "") + "，" +
                if (pkg == FRAMEWORK || staleFailure(s)) "重启手机后更新" else "重载后更新"
            Level.Unselected -> if (pkg == FRAMEWORK) "在 LSPosed 中勾选，然后重启手机" else "在 LSPosed 中勾选"
            Level.Partial -> offFeatures(s).joinToString("、") + " 未生效"
            else -> null
        }
        return ProcessRow(pkg, name, level, note)
    }

    /** A failure recorded this boot by a different module build than the one installed now. */
    private fun staleFailure(s: RuntimeStatus) = s.fresh && s.moduleVersion != TargetBuild.MODULE_VERSION
            && (s.state == RuntimeStatus.State.FAILED || s.state == RuntimeStatus.State.UNSUPPORTED)

    fun processes(link: Link, r: Receipts) = processes.map { (pkg, name) -> process(pkg, name, link, r.of(pkg)) }

    /** Parts reported by the processes running this build; stale or older receipts say nothing. */
    fun compat(r: Receipts): List<CompatRow> = processes.flatMap { (pkg, name) ->
        val s = r.of(pkg)
        if (!s.fresh || !s.currentModule()) emptyList()
        else CompatLog.decode(s.compat).map { CompatRow(pkg, name, it.feature(), it.on(), it.detail()) }
    }

    private fun offFeatures(s: RuntimeStatus) = CompatLog.decode(s.compat).filter { !it.on() }.map { it.feature() }

    private fun callbacks(pkg: String, s: RuntimeStatus): Long = when (pkg) {
        ReloadPlan.SYSTEMUI -> s.touch
        ReloadPlan.WALLPAPER -> s.entry + s.wallpaperFrames
        else -> s.entry
    }

    /** LSPosed link, settings sync and, once SystemUI reports, which revision it read. */
    fun connection(config: MotionConfig, link: Link, r: Receipts): List<Fact> = buildList {
        add(if (link.connected) Fact("LSPosed", "已连接", Tone.Good) else Fact("LSPosed", "未连接", Tone.Idle))
        add(when {
            link.problem.isNotEmpty() -> Fact("设置同步", "出错", Tone.Bad, link.problem)
            link.pending && link.connected -> Fact("设置同步", "正在写入", Tone.Idle)
            link.pending -> Fact("设置同步", "仅保存在本机", Tone.Warn)
            link.connected -> Fact("设置同步", "已同步第 ${config.revision} 版", Tone.Good)
            else -> Fact("设置同步", "仅保存在本机", Tone.Idle)
        })
        if (r.ui.currentModule()) add(
            if (r.ui.configRevision >= config.revision) Fact("系统界面读取", "第 ${r.ui.configRevision} 版", Tone.Good)
            else Fact("系统界面读取", "还在第 ${r.ui.configRevision} 版", Tone.Warn),
        )
    }

    /**
     * One line under the late-desktop switch, or null while it is off. Only SystemUI decides
     * this now (it withholds the early desktop resume), so the framework is not consulted.
     */
    fun desktopAnimation(config: MotionConfig, r: Receipts): LineStatus? {
        if (!config.lateDesktopAnimation) return null
        if (!r.ui.currentModule()) return LineStatus(Tone.Idle, "还没收到系统界面的回执")
        return desktopState(r.ui.desktopAnimationState, r.ui.desktopAnimationDetail)
    }

    /** "released"/"played" only prove the hand-off happened, never what the launcher drew. */
    fun desktopState(state: String, detail: String): LineStatus = when (state) {
        "disabled" -> LineStatus(Tone.Idle, "上次手势时还是关闭的，下次锁屏后更新")
        "requires-framework-restart" -> LineStatus(Tone.Warn, "需要重启手机，系统框架还是旧版")
        "framework-ready", "ready", "armed" -> LineStatus(Tone.Good, "已就绪，等待下次解锁回到桌面")
        "deferred" -> LineStatus(Tone.Idle, "正在等待解锁完成")
        "released" -> LineStatus(Tone.Good, "上次解锁完成后才恢复桌面，动画请自己确认")
        "waiting-launcher" -> LineStatus(Tone.Idle, "等待桌面注册动画")
        "waiting-handoff" -> LineStatus(Tone.Idle, "等待过渡交接")
        "played" -> LineStatus(Tone.Good, "上次已交给系统播放，画面请自己确认")
        "cancelled" -> LineStatus(Tone.Idle, "上次已取消" + detail.let { if (it.isEmpty()) "" else "（$it）" })
        "unsupported", "failed" -> LineStatus(Tone.Bad, "当前不可用" + detail.let { if (it.isEmpty()) "" else "（$it）" })
        else -> LineStatus(Tone.Idle, "还没收到回执")
    }
}
