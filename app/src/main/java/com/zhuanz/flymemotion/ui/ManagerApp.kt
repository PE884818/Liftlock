package com.zhuanz.flymemotion.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zhuanz.flymemotion.PageActivity
import com.zhuanz.flymemotion.ui.screen.HomePage
import com.zhuanz.flymemotion.ui.screen.MotionPage
import com.zhuanz.flymemotion.ui.screen.MorePage
import com.zhuanz.flymemotion.ui.component.rememberBlurBackdrop
import com.zhuanz.flymemotion.ui.theme.AppTheme
import com.zhuanz.flymemotion.ui.theme.isInDarkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The sub-pages, each opened as its own PageActivity. */
sealed interface Route {
    val name: String
    data object Tuning : Route { override val name = "tuning" }
    data object Region : Route { override val name = "region" }
    data object Diagnostics : Route { override val name = "diagnostics" }
    data object Guide : Route { override val name = "guide" }
    data object Licenses : Route { override val name = "licenses" }
    data object Donate : Route { override val name = "donate" }
    data class LicenseText(val title: String, val asset: String) : Route { override val name = "license" }
}

const val TAB_HOME = 0
const val TAB_MOTION = 1
const val TAB_MORE = 2

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ManagerApp(vm: ManagerViewModel, initialTab: Int) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    AppTheme(ui.themeMode) {
        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).semantics { testTagsAsResourceId = true }) {
            MainScreen(vm, ui, initialTab) { route -> context.startActivity(PageActivity.intentFor(context, route)) }
        }
    }
}

private val PagerSpring = spring<Float>(dampingRatio = .9f, stiffness = 380f)

@Composable
private fun MainScreen(vm: ManagerViewModel, ui: UiSettings, initialTab: Int, open: (Route) -> Unit) {
    val pager = rememberPagerState(initialPage = initialTab, pageCount = { 3 })
    var selected by remember { mutableIntStateOf(initialTab) }
    var navigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val labels = listOf("主页", "手感", "更多")
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings)

    LaunchedEffect(pager.currentPage) {
        if (!navigating && selected != pager.currentPage) selected = pager.currentPage
    }

    // Tab taps travel the pager on one spring, like HyperMusicCover, instead of a page jump.
    fun go(index: Int) {
        if (index == selected) return
        navJob?.cancel()
        selected = index
        navigating = true
        navJob = scope.launch {
            val mine = coroutineContext.job
            try {
                pager.scroll(MutatePriority.UserInput) {
                    val page = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
                    val distance = (index - pager.currentPage - pager.currentPageOffsetFraction) * page
                    var previous = 0f
                    animate(0f, distance, animationSpec = PagerSpring) { value, _ -> previous += scrollBy(value - previous) }
                }
                if (pager.currentPage != index) pager.scrollToPage(index)
            } finally {
                if (navJob == mine) {
                    navigating = false
                    if (pager.currentPage != index) selected = pager.currentPage
                }
            }
        }
    }

    BackHandler(enabled = selected != TAB_HOME) { go(TAB_HOME) }

    val backdrop = rememberBlurBackdrop(ui.blur)
    Scaffold(
        bottomBar = { BottomBar(ui.floatingNav, labels, icons, selected, backdrop, ::go) },
    ) { padding ->
        val barHeight = padding.calculateBottomPadding()
        Box(
            Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MiuixTheme.colorScheme.surface),
        ) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, overscrollEffect = null, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    TAB_HOME -> HomePage(vm, ui.blur, barHeight, open)
                    TAB_MOTION -> MotionPage(vm, ui.blur, barHeight, open)
                    else -> MorePage(vm, ui, barHeight, open)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    floating: Boolean,
    labels: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selected: Int,
    backdrop: LayerBackdrop?,
    onSelect: (Int) -> Unit,
) {
    if (floating) {
        val shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
        val dark = isInDarkTheme()
        FloatingNavigationBar(
            modifier = if (backdrop != null) Modifier.textureBlur(
                backdrop = backdrop, shape = shape, blurRadius = 25f,
                colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .4f)))),
                highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
            ) else Modifier,
            color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        ) {
            labels.forEachIndexed { i, label ->
                FloatingNavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, icon = icons[i], label = label,
                    modifier = Modifier.testTag("tab_$i"))
            }
        }
    } else {
        val color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
        Box(
            if (backdrop != null) Modifier.textureBlur(
                backdrop = backdrop, shape = RectangleShape, blurRadius = 25f,
                colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = .5f)))),
            ) else Modifier,
        ) {
            // The bar has a fixed height; at very large font scales the labels would be clipped.
            val fontScale = LocalDensity.current.fontScale
            NavigationBar(
                color = color,
                mode = when {
                    fontScale >= 1.8f -> NavigationBarDisplayMode.IconOnly
                    fontScale >= 1.3f -> NavigationBarDisplayMode.IconWithSelectedLabel
                    else -> NavigationBarDisplayMode.IconAndText
                },
            ) {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, icon = icons[i], label = label,
                        modifier = Modifier.testTag("tab_$i"))
                }
            }
        }
    }
}
