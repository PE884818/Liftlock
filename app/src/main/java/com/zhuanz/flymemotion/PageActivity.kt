package com.zhuanz.flymemotion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhuanz.flymemotion.ui.ManagerViewModel
import com.zhuanz.flymemotion.ui.Route
import com.zhuanz.flymemotion.ui.screen.DiagnosticsPage
import com.zhuanz.flymemotion.ui.screen.DonatePage
import com.zhuanz.flymemotion.ui.screen.GuidePage
import com.zhuanz.flymemotion.ui.screen.LicenseTextPage
import com.zhuanz.flymemotion.ui.screen.LicensesPage
import com.zhuanz.flymemotion.ui.screen.RegionPage
import com.zhuanz.flymemotion.ui.screen.TuningPage
import com.zhuanz.flymemotion.ui.theme.AppTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One activity per sub-page, HyperMusicCover's house pattern: the system supplies the HyperOS
 * activity transition and predictive back, and each page reads the same back end as the manager.
 */
class PageActivity : ComponentActivity() {
    private val vm: ManagerViewModel by viewModels()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        val route = routeOf(intent) ?: run { finish(); return }
        setContent {
            val ui by vm.ui.collectAsState()
            AppTheme(ui.themeMode) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).semantics { testTagsAsResourceId = true }) {
                    val back = { finish() }
                    val open: (Route) -> Unit = { startActivity(intentFor(this@PageActivity, it)) }
                    when (route) {
                        Route.Tuning -> TuningPage(vm, ui.blur, back)
                        Route.Region -> RegionPage(vm, ui.blur, back)
                        Route.Diagnostics -> DiagnosticsPage(vm, ui.blur, back)
                        Route.Guide -> GuidePage(vm, ui.blur, back)
                        Route.Licenses -> LicensesPage(ui.blur, back, open)
                        Route.Donate -> DonatePage(ui.blur, back)
                        is Route.LicenseText -> LicenseTextPage(route, ui.blur, back)
                    }
                }
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.pollReceipts() } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ASSET = "asset"

        fun intentFor(context: Context, route: Route): Intent = Intent(context, PageActivity::class.java).apply {
            putExtra(EXTRA_ROUTE, route.name)
            if (route is Route.LicenseText) putExtra(EXTRA_TITLE, route.title).putExtra(EXTRA_ASSET, route.asset)
        }

        private fun routeOf(intent: Intent): Route? = when (intent.getStringExtra(EXTRA_ROUTE)) {
            Route.Tuning.name -> Route.Tuning
            Route.Region.name -> Route.Region
            Route.Diagnostics.name -> Route.Diagnostics
            Route.Guide.name -> Route.Guide
            Route.Licenses.name -> Route.Licenses
            Route.Donate.name -> Route.Donate
            "license" -> {
                val asset = intent.getStringExtra(EXTRA_ASSET)
                // Only bundled licence texts can be opened; the activity is not exported anyway.
                if (asset != null && (asset.startsWith("licenses/") || asset.startsWith("third_party_licenses/")))
                    Route.LicenseText(intent.getStringExtra(EXTRA_TITLE).orEmpty(), asset) else null
            }
            else -> null
        }
    }
}
