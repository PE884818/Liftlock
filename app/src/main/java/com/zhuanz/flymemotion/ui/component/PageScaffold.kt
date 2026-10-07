package com.zhuanz.flymemotion.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** A recorded layer of the page for the blurred bars, or null when blur is off or unsupported. */
@Composable
fun rememberBlurBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * The frame every page uses, after HyperMusicCover's PageScaffold: large-title bar that folds on
 * scroll, a progressive blur under it, miuix damped overscroll, and the top inset placed in the
 * list's content padding so content scrolls under the bar. [pinned] stays above the list and
 * outside its scroll, for the pages whose subject is a preview the user watches while dragging.
 */
@Composable
fun PageScaffold(
    title: String,
    blur: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    bottomInset: Dp? = null,
    actions: @Composable RowScope.() -> Unit = {},
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(blur)
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    val bottom = (bottomInset ?: WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) + 24.dp
    Scaffold(
        modifier = modifier,
        popupHost = {},
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { if (onBack != null) BackButton(onBack) },
                    actions = actions,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            if (pinned == null) {
                PageList(scrollBehavior, PaddingValues(top = inner.calculateTopPadding(), bottom = bottom), content)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    pinned()
                    PageList(scrollBehavior, PaddingValues(bottom = bottom), content)
                }
            }
        }
    }
}

@Composable
private fun PageList(scrollBehavior: ScrollBehavior, padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        // overScrollVertical replaces Compose's stretch; both at once fight at the ends.
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = padding,
        content = content,
    )
}

@Composable
fun BlurredBar(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    Box {
        if (backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 10f),
                        blurRadius = 15f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = .3f))),
                        ),
                    ),
            )
        }
        content()
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.graphicsLayer { if (rtl) scaleX = -1f },
        )
    }
}
