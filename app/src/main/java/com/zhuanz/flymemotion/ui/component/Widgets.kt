package com.zhuanz.flymemotion.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.ui.Tone
import com.zhuanz.flymemotion.ui.theme.ToneColors
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * One group of rows, iOS/MIUI settings style: an optional small title, a card, and at most one
 * footer that explains the whole group. Explanations live here, not in a summary under every row.
 */
@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SmallTitle(title, insideMargin = PaddingValues(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp))
        else Spacer(Modifier.height(12.dp))
        Card(Modifier.padding(horizontal = 12.dp), content = content)
        if (footer != null) Footer(footer, Modifier.padding(bottom = 8.dp))
    }
}

@Composable
fun Footer(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 8.dp),
    )
}

/** Normal states read in the quiet secondary colour; only states that need the user are tinted. */
@Composable
fun toneText(tone: Tone): Color =
    if (tone == Tone.Warn || tone == Tone.Bad) ToneColors.accent(tone) else MiuixTheme.colorScheme.onSurfaceVariantActions

/**
 * A title with its value on the right, the one-fact row. [note] is for the rare line that asks the
 * user to do something; [onClick] adds the arrow.
 */
@Composable
fun ValueRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Idle,
    note: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val end: @Composable RowScope.() -> Unit = {
        if (value != null) Text(value, style = MiuixTheme.textStyles.body2, color = toneText(tone),
            modifier = Modifier.padding(start = 12.dp))
    }
    if (onClick != null) ArrowPreference(title = title, summary = note, endActions = end, onClick = onClick,
        enabled = enabled, modifier = modifier)
    else BasicComponent(title = title, summary = note, endActions = end, enabled = enabled, modifier = modifier)
}

/**
 * HyperMusicCover's slider row: title and live value above a miuix slider. The default value is a
 * visible key point that ticks under the finger without pulling it (magnet off). Saving happens in
 * [onFinished], once per drag, the same moment the old manager wrote.
 */
@Composable
fun ValueSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    tag: String,
    detent: Float? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    // miuix reports onValueChangeFinished only when a drag stops; accessibility setProgress never
    // drags. Settling for a moment saves too, and the store ignores a save that changes nothing.
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(value) {
        delay(600)
        finished()
    }
    Column(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            enabled = enabled,
            endActions = {
                Text(valueText, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            },
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            hapticEffect = if (detent != null) SliderDefaults.SliderHapticEffect.Step else SliderDefaults.DefaultHapticEffect,
            showKeyPoints = detent != null,
            keyPoints = detent?.let { listOf(it) },
            magnetThreshold = 0f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .testTag(tag)
                .semantics { contentDescription = title },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/** A two-button miuix window dialog. [destructive] colours the confirm button as a warning. */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    summary: String?,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    WindowDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        // A window of its own: test tags must be re-exposed for this composition root.
        Column(Modifier.semantics { testTagsAsResourceId = true }) {
            content()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    confirm,
                    onClick = { onConfirm(); onDismiss() },
                    modifier = Modifier.weight(1f).testTag("dialog_confirm"),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
