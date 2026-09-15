package ru.dvedev.me.cupola.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.Segmented
import ru.dvedev.me.cupola.ui.components.ZoneDivider
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaColors
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import ru.dvedev.me.cupola.ui.theme.DarkCupolaColors
import ru.dvedev.me.cupola.ui.theme.LightCupolaColors
import ru.dvedev.me.cupola.ui.theme.SpectrogramColormap
import ru.dvedev.me.cupola.ui.theme.ThemeMode

/**
 * Design-token gallery (T-004): every colour, text style, badge, button and the spectrogram
 * colormap in the current theme, plus a strip of the opposite palette. Theme switch is live.
 * Replaced as the launcher content by the Analysis screen in T-050; kept reachable from
 * settings for design checks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokensPreviewScreen(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    Column(
        Modifier
            .fillMaxSize()
            .background(c.panel)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ZoneHeader(
            left = {
                Label("Купол", color = c.ink)
                Spacer(Modifier.width(10.dp))
                Label("токены v0.1")
            },
            right = {
                Segmented(
                    options = ThemeMode.entries,
                    selected = mode,
                    onSelect = onModeChange,
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "Система"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        }
                    },
                )
            },
        )

        Section("Цвета")
        FlowRow(
            Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            c.swatches().forEach { (name, color) -> Swatch(name, color) }
        }

        Section("Типографика")
        Column(Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Соль¹", style = t.note, color = c.ink)
                Text("G4", style = t.noteEn, color = c.dim)
                Text("+4 ¢", style = t.cents, color = c.ok)
            }
            Text("392.4 Гц · обертонов 14 · вибрато 5.6 Гц ±58 ¢", style = t.sub, color = c.dim)
            Text("Слишком громко для купола — тише", style = t.hint, color = c.mut)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("02:14", style = t.stats, color = c.ink)
                Text("128", style = t.stats, color = c.goldInk)
                Text("+3.1 dB", style = t.ringValue, color = c.goldInk)
            }
            Text("Заголовок раздела настроек", style = t.title, color = c.ink)
            Text(
                "Обычный текст: подсказки описывают, что произошло со звуком, и предлагают только «тише», «шаг назад» или «стоп».",
                style = t.body,
                color = c.mut,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Подпись 11")
                Text("ось 9", style = t.axis, color = c.dim)
            }
        }

        Section("Бейджи и кнопки")
        FlowRow(
            Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Badge("G4")
            Badge("живой")
            Badge("купол")
            PillButton("Старт", onClick = {}, style = PillStyle.Primary)
            PillButton("Стоп", onClick = {}, style = PillStyle.Muted)
            PillButton("Пауза", onClick = {}, style = PillStyle.Outline)
            PillButton("Пауза", onClick = {}, style = PillStyle.Outline, active = true)
        }

        Section("Колормап спектрограммы")
        ColormapStrip(CupolaTheme.colormap, Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp))

        Section("Статусы")
        Row(Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusDot("ok ±10 ¢", c.ok)
            StatusDot("warn ±25 ¢", c.warn)
            StatusDot("bad", c.bad)
        }

        val other = if (c.isDark) LightCupolaColors else DarkCupolaColors
        Section(if (c.isDark) "Светлая тема" else "Тёмная тема")
        Box(Modifier.fillMaxWidth().background(other.panel).padding(CupolaDimens.paddingH)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                other.swatches().forEach { (_, color) -> Box(Modifier.size(22.dp).background(color)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    ZoneDivider()
    ZoneHeader(left = { Label(title) })
}

@Composable
private fun Swatch(name: String, color: Color) {
    val c = CupolaTheme.colors
    Column(horizontalAlignment = Alignment.Start) {
        Box(Modifier.size(width = 84.dp, height = 40.dp).background(color))
        Text(name, style = CupolaTheme.type.axis, color = c.mut)
        Text("#%06X".format(0xFFFFFF and color.toArgb()), style = CupolaTheme.type.axis, color = c.dim)
    }
}

@Composable
private fun StatusDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(CupolaDimens.hintDot).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(text, style = CupolaTheme.type.hint, color = CupolaTheme.colors.mut)
    }
}

@Composable
private fun ColormapStrip(colormap: SpectrogramColormap, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val n = SpectrogramColormap.SIZE
        val w = size.width / n
        for (i in 0 until n) {
            drawRect(
                color = Color(colormap.lut[i]),
                topLeft = androidx.compose.ui.geometry.Offset(i * w, 0f),
                size = androidx.compose.ui.geometry.Size(w + 1f, size.height),
            )
        }
    }
}

private fun CupolaColors.swatches(): List<Pair<String, Color>> = listOf(
    "panel" to panel, "panel2" to panel2, "line" to line, "line2" to line2,
    "ink" to ink, "mut" to mut, "dim" to dim,
    "gold" to gold, "goldInk" to goldInk, "violet" to violet, "violetInk" to violetInk,
    "ok" to ok, "warn" to warn, "bad" to bad, "bg" to bg,
)

@Preview(name = "light", showBackground = true, widthDp = 480, heightDp = 1000)
@Composable
private fun PreviewLight() {
    CupolaTheme(dark = false) { TokensPreviewScreen(ThemeMode.LIGHT) {} }
}

@Preview(name = "dark", showBackground = true, widthDp = 480, heightDp = 1000)
@Composable
private fun PreviewDark() {
    CupolaTheme(dark = true) { TokensPreviewScreen(ThemeMode.DARK) {} }
}
