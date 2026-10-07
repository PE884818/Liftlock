package com.zhuanz.flymemotion.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhuanz.flymemotion.MotionApplication
import com.zhuanz.flymemotion.RuntimeProvider
import com.zhuanz.flymemotion.core.BottomGestureRegion
import com.zhuanz.flymemotion.core.MotionConfig
import com.zhuanz.flymemotion.core.ReloadPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where one manager-initiated reload stands. Confirmation comes only from a new process receipt. */
sealed interface ReloadPhase {
    data object Running : ReloadPhase
    data class Sent(val at: Long) : ReloadPhase
    data class Confirmed(val version: String) : ReloadPhase
    data object TimedOut : ReloadPhase
    data class Finished(val message: String) : ReloadPhase
}

data class ReloadState(val busy: Boolean = false, val message: String = "", val phases: Map<String, ReloadPhase> = emptyMap())

data class UiSettings(val themeMode: String = "System", val floatingNav: Boolean = false, val blur: Boolean = true)

/**
 * The only bridge between the screens and the existing back end. Reads the real store, reloader
 * and RuntimeProvider; writes only through ConfigurationStore.save. Opening the UI never writes.
 */
class ManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MotionApplication.configuration()
    private val reloader = MotionApplication.reloader()
    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(store.get())
    val config: StateFlow<MotionConfig> = _config.asStateFlow()
    private val _link = MutableStateFlow(readLink())
    val link: StateFlow<Link> = _link.asStateFlow()
    private val _receipts = MutableStateFlow(Receipts.Empty)
    val receipts: StateFlow<Receipts> = _receipts.asStateFlow()
    private val _reload = MutableStateFlow(ReloadState(reloader.busy(), reloader.message()))
    val reload: StateFlow<ReloadState> = _reload.asStateFlow()
    private val _ui = MutableStateFlow(readUi())
    val ui: StateFlow<UiSettings> = _ui.asStateFlow()
    private val _related = MutableStateFlow<List<String>>(emptyList())
    val related: StateFlow<List<String>> = _related.asStateFlow()

    private var reloadTarget: String? = null
    private val listener = Runnable { onBackendChanged() }

    init {
        store.addListener(listener)
        reloader.addListener(listener)
        viewModelScope.launch { _related.value = withContext(Dispatchers.IO) { relatedModules() } }
    }

    override fun onCleared() {
        store.removeListener(listener)
        reloader.removeListener(listener)
    }

    private fun readLink() = Link(store.connected(), store.pending(), store.problem().orEmpty(), store.framework(), store.scope())

    private fun onBackendChanged() {
        _config.value = store.get()
        _link.value = readLink()
        val busy = reloader.busy()
        val message = reloader.message()
        _reload.update { state ->
            val target = reloadTarget
            if (target == null || busy) state.copy(busy = busy, message = message)
            else {
                reloadTarget = null
                val phase = if (message.contains("已收到重载请求")) ReloadPhase.Sent(SystemClock.elapsedRealtime())
                else ReloadPhase.Finished(message)
                state.copy(busy = false, message = message, phases = state.phases + (target to phase))
            }
        }
    }

    /** Polls receipts while the caller (the resumed activity) keeps this coroutine alive. */
    suspend fun pollReceipts() {
        while (true) {
            refreshReceipts()
            delay(2000)
        }
    }

    fun onResume() {
        store.refreshScope()
        onBackendChanged()
    }

    suspend fun refreshReceipts() {
        val context = getApplication<Application>()
        val next = withContext(Dispatchers.IO) {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            try {
                val bundle = context.contentResolver.call(RuntimeProvider.URI, "read", null, null)
                Receipts.read(bundle, SystemClock.elapsedRealtime(), boot)
            } catch (e: RuntimeException) {
                Receipts.read(null, SystemClock.elapsedRealtime(), boot, "读取回执失败：${e.javaClass.simpleName}")
            }
        }
        _receipts.value = next
        _reload.update { state ->
            state.copy(phases = state.phases.mapValues { (pkg, phase) ->
                if (phase !is ReloadPhase.Sent) phase
                else if (next.of(pkg).currentModule()) ReloadPhase.Confirmed(next.of(pkg).moduleVersion)
                else if (SystemClock.elapsedRealtime() - phase.at > 150_000) ReloadPhase.TimedOut
                else phase
            })
        }
    }

    fun reload(pkg: String) {
        if (reloader.busy()) return
        reloadTarget = pkg
        _reload.update { it.copy(busy = true, phases = it.phases + (pkg to ReloadPhase.Running)) }
        reloader.reload(pkg)
        onBackendChanged()
    }

    fun setEnabled(value: Boolean) {
        if (store.get().enabled != value) store.save(store.get().withEnabled(value))
    }

    fun setLateDesktop(value: Boolean) {
        if (store.get().lateDesktopAnimation != value) store.save(store.get().withLateDesktopAnimation(value))
    }

    /** [MotionConfig.FOLLOW_SCREEN] follows the panel; otherwise the bottom corners in dp. */
    fun setCornerRadius(dp: Float) {
        if (store.get().cornerRadiusDp != dp) store.save(store.get().withCornerRadius(dp))
    }

    fun saveMotion(params: MotionParams) {
        if (MotionParams.of(store.get()) != params) store.save(params.applyTo(store.get()))
    }

    fun applyPreset(preset: MotionPreset) {
        if (!preset.matches(store.get())) store.save(preset.applyTo(store.get()))
    }

    fun saveRegion(region: BottomGestureRegion) {
        if (store.get().bottomRegion != region) store.save(store.get().withBottomRegion(region))
    }

    /** Keeps the master switch, late-desktop and corner choices; each group resets only when asked. */
    fun restoreDefaults(motion: Boolean, region: Boolean) {
        if (!motion && !region) return
        val c = store.get()
        val f = MotionConfig.defaults()
        val m = if (motion) f else c
        store.save(MotionConfig(c.enabled, c.lateDesktopAnimation, m.stiffness, m.expandDamping, m.closeDamping,
            m.minimumVelocityDp, m.threshold, m.blurStrength, c.revision + 1,
            if (region) BottomGestureRegion.defaults() else c.bottomRegion, c.cornerRadiusDp))
    }

    /** Fault stop: only the master switch, every other choice is kept. */
    fun emergencyDisable() = setEnabled(false)

    private fun readUi() = UiSettings(
        uiPrefs.getString("themeMode", "System") ?: "System",
        uiPrefs.getBoolean("floatingNav", false),
        uiPrefs.getBoolean("blur", true),
    )

    fun updateUi(next: UiSettings) {
        _ui.value = next
        uiPrefs.edit {
            putString("themeMode", next.themeMode)
            putBoolean("floatingNav", next.floatingNav)
            putBoolean("blur", next.blur)
        }
    }

    private fun relatedModules(): List<String> {
        val pm = getApplication<Application>().packageManager
        return listOf(
            "com.github.zyl6932.HyperMusicCover" to "HyperMusicCover",
            "com.kiminonawa.HyperLight" to "HyperLight",
            "btm.m.os4.systemuihook" to "SystemUI Hook",
        ).mapNotNull { (pkg, name) ->
            try { pm.getApplicationInfo(pkg, 0); name } catch (_: PackageManager.NameNotFoundException) { null }
        }
    }

    companion object {
        val reloadTargets = listOf(ReloadPlan.SYSTEMUI, ReloadPlan.SETTINGS, ReloadPlan.WALLPAPER)
    }
}
