package ru.dvedev.me.cupola.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing and size tokens of the Analysis screen (SPEC §15.3, mock `design/mock-analysis-v0.1.html`). */
object CupolaDimens {
    /** Dividers between zones and under headers. */
    val divider = 1.dp

    /** Horizontal padding of the top bar and zone headers. */
    val paddingH = 14.dp
    val topBarPaddingV = 8.dp
    val zoneHeaderPaddingV = 6.dp

    /** Badge: `border 1 px · radius 4 · padding 1×6`. */
    val badgeCorner = 4.dp
    val badgePaddingH = 6.dp
    val badgePaddingV = 1.dp

    /** Pill buttons in the top bar. */
    val buttonPaddingH = 22.dp
    val buttonPaddingV = 8.dp
    val pausePaddingH = 14.dp
    val gearSize = 26.dp

    /** Note zone. */
    val hintRowHeight = 30.dp
    val hintDot = 9.dp
    val scaleBarThickness = 2.dp
    val scalePin = 24.dp

    /** Spectrum zone height in portrait; landscape left column width. */
    val spectrumHeight = 270.dp
    val landscapeNoteWidth = 360.dp

    /** Tablets (sw ≥ 600 dp) get haptics off by default (SPEC §15.5). */
    val tabletMinWidth = 600.dp
}
