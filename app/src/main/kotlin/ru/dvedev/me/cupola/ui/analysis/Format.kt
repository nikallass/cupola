package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.abs

/** Cents thresholds of the note colour (SPEC §6.1), from settings. */
data class CentsThresholds(val ok: Double = 10.0, val warn: Double = 25.0)

val LocalCentsThresholds = compositionLocalOf { CentsThresholds() }

@Composable
fun centsColor(cents: Double): Color {
    val c = CupolaTheme.colors
    val th = LocalCentsThresholds.current
    val a = abs(cents)
    return when {
        cents.isNaN() -> c.dim
        a <= th.ok -> c.ok
        a <= th.warn -> c.warn
        else -> c.bad
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

fun formatHz(hz: Double): String = "%.1f".format(hz)

fun formatDb(db: Double): String = (if (db >= 0) "+" else "−") + "%.1f".format(abs(db))

fun formatKHz(hz: Int): String = if (hz >= 1000) "%.1fk".format(hz / 1000.0).replace(".0k", "k").replace(",0k", "k") else hz.toString()

/**
 * `drawText` that skips labels which would not fit inside the canvas: Compose throws when
 * the remaining constraints go negative (a harmonic below the axis range, a label at the edge).
 */
fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle, measured: TextLayoutResult? = null) {
    val m = measured ?: measurer.measure(text, style)
    if (topLeft.x < 0f || topLeft.y < 0f) return
    if (topLeft.x + m.size.width > size.width || topLeft.y + m.size.height > size.height) return
    drawText(measurer, text, topLeft, style)
}
