package com.zhuanz.flymemotion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhuanz.flymemotion.ui.ManagerApp
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.TAB_HOME
import kotlinx.coroutines.launch

/**
 * The manager. Class name and the `page="lockscreen"` extra are a contract with the Settings hook
 * (SettingsHooks), which opens this activity from the system lock screen settings page.
 */
class ManagerActivity : ComponentActivity() {
    private val vm: ManagerViewModel by viewModels()

    /** For the emulator instrumentation only. */
    fun viewModel(): ManagerViewModel = vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        // The settings entry lands on the home page too: the master switch lives there now.
        setContent { ManagerApp(vm, TAB_HOME) }
        // Receipts are polled only while the manager is on screen, off the main thread.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.pollReceipts() } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_LOCKSCREEN = "lockscreen"
    }
}
