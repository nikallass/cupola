package ru.dvedev.me.cupola.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.dvedev.me.cupola.R

/**
 * Manrope variable font (OFL), weights 400–800 through the `wght` axis. The axis value
 * is passed explicitly: without it the platform loads the file's default instance and
 * every weight renders the same.
 */
@OptIn(ExperimentalTextApi::class)
val Manrope: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { weight ->
        Font(
            resId = R.font.manrope_variable,
            weight = FontWeight(weight),
            style = FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** IBM Plex Mono (OFL), static Regular / Medium / SemiBold. */
val PlexMono: FontFamily = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold),
)

private const val TABULAR_NUMS = "tnum"

private val noLineHeightPadding = PlatformTextStyle(includeFontPadding = false)
private val centeredTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun sans(size: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.em,
    platformStyle = noLineHeightPadding,
    lineHeightStyle = centeredTrim,
)

private fun mono(size: Float, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = PlexMono,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = TABULAR_NUMS,
    platformStyle = noLineHeightPadding,
    lineHeightStyle = centeredTrim,
)

/**
 * Text styles of the Analysis screen (SPEC §15.3–15.4). Sizes are the dp values of the
 * mock expressed in sp. Mono styles carry `tnum` so digits keep a fixed width while
 * they change 100 times a second.
 */
@Immutable
data class CupolaTypography(
    /** The note name itself: `Соль¹`. */
    val note: TextStyle = sans(84, FontWeight.Bold, tracking = -0.025f),
    /** Scientific name next to the note: `G4`. */
    val noteEn: TextStyle = mono(38f, FontWeight.Medium),
    /** Cents deviation: `+4 ¢`. */
    val cents: TextStyle = mono(40f, FontWeight.SemiBold),
    /** Ring value under the cupola arc. */
    val ringValue: TextStyle = mono(30f, FontWeight.SemiBold),
    /** Session time and points in the top bar. */
    val stats: TextStyle = mono(15f, FontWeight.Medium),
    /** Sub-line under the note: `392.4 Гц · обертонов 14 · …`. */
    val sub: TextStyle = mono(14f, FontWeight.Normal, tracking = 0.02f),
    /** One-line hint in the reserved row of the note zone. */
    val hint: TextStyle = sans(17, FontWeight.Medium),
    /** Start / Stop / Pause buttons. */
    val button: TextStyle = sans(14, FontWeight.SemiBold),
    /** Uppercase badge in zone headers: `G4`, `ЖИВОЙ`. */
    val badge: TextStyle = mono(10.5f, FontWeight.Medium, tracking = 0.12f),
    /** Uppercase captions: top bar left part, zone header right part, axis labels. */
    val label: TextStyle = mono(11f, FontWeight.Normal, tracking = 0.10f),
    /** Axis tick text on canvases. */
    val axis: TextStyle = mono(9f, FontWeight.Normal),
    /** Regular body text (settings, onboarding, glossary). */
    val body: TextStyle = sans(14, FontWeight.Normal),
    /** Screen titles in settings / onboarding. */
    val title: TextStyle = sans(18, FontWeight.SemiBold, tracking = -0.01f),
)

val LocalCupolaTypography = staticCompositionLocalOf { CupolaTypography() }
