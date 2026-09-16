package ru.dvedev.me.cupola.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

/** 1 px line between zones. */
@Composable
fun ZoneDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(CupolaDimens.divider)
            .background(CupolaTheme.colors.line),
    )
}

/**
 * Header strip of a zone: `panel2` at 60 % with a 1 px bottom line, left and right slots.
 * Right slot is typically uppercase [CupolaTheme.type.label] text.
 */
@Composable
fun ZoneHeader(
    modifier: Modifier = Modifier,
    /** When given, a tap on the header toggles the zone (owner 2026‑09‑16) and a chevron shows its state. */
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(CupolaTheme.colors.zoneHeader)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = CupolaDimens.paddingH, vertical = CupolaDimens.zoneHeaderPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (collapsed != null) {
                Text(if (collapsed) "▸" else "▾", style = CupolaTheme.type.label, color = CupolaTheme.colors.dim)
                Spacer(Modifier.width(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { left() }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) { right() }
        }
        ZoneDivider()
    }
}

/** Uppercase mono badge in violet: `G4`, `ЖИВОЙ`, `КУПОЛ`. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    Text(
        text = text.uppercase(),
        style = CupolaTheme.type.badge,
        color = c.violetInk,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .border(1.dp, c.violet.copy(alpha = 0.35f), RoundedCornerShape(CupolaDimens.badgeCorner))
            .padding(horizontal = CupolaDimens.badgePaddingH, vertical = CupolaDimens.badgePaddingV),
    )
}

/** Uppercase mono caption in [CupolaTheme.colors.dim]. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = CupolaTheme.colors.dim) {
    Text(text.uppercase(), style = CupolaTheme.type.label, color = color, maxLines = 1, modifier = modifier)
}

enum class PillStyle { Primary, Muted, Outline }

/**
 * Pill button of the top bar. [PillStyle.Primary] is the violet «Старт», [PillStyle.Muted]
 * the quiet outlined «Стоп», [PillStyle.Outline] the transparent «Пауза».
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    active: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    /** Tighter horizontal padding for narrow top bars (phones). */
    compact: Boolean = false,
    /** Disabled: dimmed and not clickable, but still in the layout. */
    enabled: Boolean = true,
) {
    val c = CupolaTheme.colors
    val (bg0, fg0, border0) = when (style) {
        PillStyle.Primary -> Triple(c.violetInk, if (c.isDark) c.panel else Color.White, Color.Transparent)
        PillStyle.Muted -> Triple(c.panel, c.mut, c.line2)
        PillStyle.Outline -> Triple(Color.Transparent, if (active) c.violetInk else c.mut, if (active) c.violet else c.line2)
    }
    // disabled = the same colours at reduced alpha (an alpha graphicsLayer here left the border stale after re-enabling)
    val dim = if (enabled) 1f else 0.45f
    val bg = if (bg0 == Color.Transparent) bg0 else bg0.copy(alpha = bg0.alpha * dim)
    val fg = fg0.copy(alpha = fg0.alpha * dim)
    val border = if (border0 == Color.Transparent) border0 else border0.copy(alpha = border0.alpha * dim)
    val padH = if (compact) CupolaDimens.compactButtonPaddingH else if (style == PillStyle.Outline) CupolaDimens.pausePaddingH else CupolaDimens.buttonPaddingH
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = padH, vertical = CupolaDimens.buttonPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = CupolaTheme.type.button, color = fg, maxLines = 1)
    }
}

/**
 * Segmented control (settings, theme switch). Selected segment is `panel2` with violet text,
 * as in the mock's `.seg`.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
) {
    val c = CupolaTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .border(1.dp, c.line, CircleShape)
            .background(c.panel),
    ) {
        options.forEach { option ->
            val on = option == selected
            Text(
                text = label(option),
                style = CupolaTheme.type.body,
                color = if (on) c.violetInk else c.mut,
                modifier = Modifier
                    .background(if (on) c.panel2 else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}
