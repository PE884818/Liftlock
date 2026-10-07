package com.zhuanz.flymemotion.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.zhuanz.flymemotion.ui.Tone

/** Line glyphs for the status card, drawn here so the APK carries no icon pack for four shapes. */
object Glyphs {
    private fun glyph(name: String, body: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 2.75f)
            arcToRelative(9.25f, 9.25f, 0f, true, true, 0f, 18.5f)
            arcToRelative(9.25f, 9.25f, 0f, true, true, 0f, -18.5f)
            body()
        }.build()

    val Ok = glyph("ok") { moveTo(7.6f, 12.3f); lineTo(10.6f, 15.2f); lineTo(16.4f, 9.2f) }
    val Warn = glyph("warn") { moveTo(12f, 7.2f); lineTo(12f, 13.2f); moveTo(12f, 16.6f); lineTo(12f, 16.7f) }
    val Bad = glyph("bad") { moveTo(8.8f, 8.8f); lineTo(15.2f, 15.2f); moveTo(15.2f, 8.8f); lineTo(8.8f, 15.2f) }
    val Wait = glyph("wait") { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.4f, 14f) }

    fun of(tone: Tone) = when (tone) {
        Tone.Good -> Ok
        Tone.Warn -> Warn
        Tone.Bad -> Bad
        Tone.Idle -> Wait
    }
}
