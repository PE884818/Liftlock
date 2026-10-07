package com.zhuanz.flymemotion.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.zhuanz.flymemotion.ui.Tone
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

fun colorSchemeMode(name: String): ColorSchemeMode =
    runCatching { ColorSchemeMode.valueOf(name) }.getOrDefault(ColorSchemeMode.System)

@Composable
fun AppTheme(themeMode: String, content: @Composable () -> Unit) {
    val mode = colorSchemeMode(themeMode)
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller) {
        // enableEdgeToEdge follows the system night mode only; an in-app light/dark choice has to
        // steer the bar icons itself, from the surface the pages actually draw on.
        val dark = isInDarkTheme()
        val view = LocalView.current
        if (!view.isInEditMode) SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        content()
    }
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    val s = MiuixTheme.colorScheme.surface
    return 0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue < 0.5f
}

/** Status palette: the HyperMusicCover green/red pair plus an amber and a calm blue. */
object ToneColors {
    @Composable
    fun container(tone: Tone): Color {
        val dark = isInDarkTheme()
        if (MiuixTheme.isDynamicColor) return when (tone) {
            Tone.Bad -> MiuixTheme.colorScheme.errorContainer
            Tone.Good -> MiuixTheme.colorScheme.secondaryContainer
            else -> MiuixTheme.colorScheme.surfaceContainer
        }
        return when (tone) {
            Tone.Good -> if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
            Tone.Warn -> if (dark) Color(0xFF3A2D12) else Color(0xFFFFF1D9)
            Tone.Bad -> if (dark) Color(0xFF3D1C1C) else Color(0xFFFDE8E8)
            Tone.Idle -> if (dark) Color(0xFF1C2538) else Color(0xFFE6EDFC)
        }
    }

    @Composable
    fun accent(tone: Tone): Color {
        if (MiuixTheme.isDynamicColor) return when (tone) {
            Tone.Bad -> MiuixTheme.colorScheme.error
            Tone.Idle -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            else -> MiuixTheme.colorScheme.primary
        }
        return when (tone) {
            Tone.Good -> Color(0xFF36D167)
            Tone.Warn -> Color(0xFFF5A524)
            Tone.Bad -> Color(0xFFDC3545)
            Tone.Idle -> Color(0xFF4D7CFE)
        }
    }
}

/** The launcher icon's teal, for the few surfaces that carry the brand. */
object Brand {
    val Light = Color(0xFF18B596)
    val Deep = Color(0xFF0B8A7A)
}
