package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.abs

/** Cents thresholds of the note colour (SPEC §6.1); adjustable in settings later. */
object CentsThresholds {
    const val OK = 10.0
    const val WARN = 25.0
}

@Composable
fun centsColor(cents: Double): Color {
    val c = CupolaTheme.colors
    val a = abs(cents)
    return when {
        cents.isNaN() -> c.dim
        a <= CentsThresholds.OK -> c.ok
        a <= CentsThresholds.WARN -> c.warn
        else -> c.bad
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

fun formatHz(hz: Double): String = "%.1f".format(hz)

fun formatDb(db: Double): String = (if (db >= 0) "+" else "−") + "%.1f".format(abs(db))

fun formatKHz(hz: Int): String = if (hz >= 1000) "%.1fk".format(hz / 1000.0).replace(".0k", "k") else hz.toString()
