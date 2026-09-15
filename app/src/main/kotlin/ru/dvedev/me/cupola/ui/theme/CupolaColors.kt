package ru.dvedev.me.cupola.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the Analysis screen (SPEC §15.4). Names follow the agreed HTML mock
 * (`design/mock-analysis-v0.1.html`) so that the two can be compared side by side.
 */
@Immutable
data class CupolaColors(
    /** Page background behind the screen panel (visible only around dialogs). */
    val bg: Color,
    /** Main panel / screen background. */
    val panel: Color,
    /** Zone headers (drawn at 60 % opacity over [panel]) and pressed segments. */
    val panel2: Color,
    /** 1 px dividers between zones. */
    val line: Color,
    /** Stronger lines: scale bar, outlined pause button, ticks. */
    val line2: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text: stats, hints, sub-lines. */
    val mut: Color,
    /** Labels, captions, axis text. */
    val dim: Color,
    /** Cupola band, spectrum fill, points. */
    val gold: Color,
    /** Gold as text colour (points counter, ring value). */
    val goldInk: Color,
    /** Target pitch line and its harmonics, focus outlines. */
    val violet: Color,
    /** Start button fill, badges. */
    val violetInk: Color,
    /** In tune / good gate state; the only green on the screen (note zone glow). */
    val ok: Color,
    /** ±10…25 ¢ and soft warnings. */
    val warn: Color,
    /** > 25 ¢ and hard warnings. */
    val bad: Color,
    /** Spectrogram colormap stops: silence → mid → loud. */
    val spectrogramStops: List<Color>,
    val isDark: Boolean,
) {
    /** Zone header background as used in the mock: `color-mix(panel2 60%, transparent)`. */
    val zoneHeader: Color get() = panel2.copy(alpha = 0.6f)
}

val LightCupolaColors = CupolaColors(
    bg = Color(0xFFF7F0E7),
    panel = Color(0xFFFFFAF2),
    panel2 = Color(0xFFEFE6D5),
    line = Color(0xFFDDD4C4),
    line2 = Color(0xFFC9BDA0),
    ink = Color(0xFF20201D),
    mut = Color(0xFF5C564D),
    dim = Color(0xFF6B6455),
    gold = Color(0xFFC59642),
    goldInk = Color(0xFF82631F),
    violet = Color(0xFF8B5CF6),
    violetInk = Color(0xFF6D28D9),
    ok = Color(0xFF2A6F53),
    warn = Color(0xFF82631F),
    bad = Color(0xFF9D3F33),
    spectrogramStops = listOf(Color(0xFFFFFAF2), Color(0xFFC59642), Color(0xFF20201D)),
    isDark = false,
)

val DarkCupolaColors = CupolaColors(
    bg = Color(0xFF1C1B18),
    panel = Color(0xFF24231F),
    panel2 = Color(0xFF2E2C27),
    line = Color(0xFF3B382F),
    line2 = Color(0xFF4D493F),
    ink = Color(0xFFEFE8DA),
    mut = Color(0xFFB5AC9C),
    dim = Color(0xFF8C8474),
    gold = Color(0xFFD6AB5A),
    goldInk = Color(0xFFE2C07A),
    violet = Color(0xFFA78BFA),
    violetInk = Color(0xFFB7A2FB),
    ok = Color(0xFF63B48E),
    warn = Color(0xFFD1A24A),
    bad = Color(0xFFD4705F),
    spectrogramStops = listOf(Color(0xFF24231F), Color(0xFFD6AB5A), Color(0xFFFAF4E8)),
    isDark = true,
)

val LocalCupolaColors = staticCompositionLocalOf { LightCupolaColors }
