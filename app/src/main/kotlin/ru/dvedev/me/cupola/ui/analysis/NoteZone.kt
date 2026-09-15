package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.analysis.HintKey
import ru.dvedev.me.cupola.analysis.SessionUiState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.notation.NoteNames
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.min

/**
 * Zone ② «Нота» (SPEC §15.3): the note, cents scale, sub-line, cupola arc, reserved hint
 * row and the only green on the screen. Tap pins the current note as the target; long
 * press on the arc opens calibration. [compact] (landscape column) stacks the arc under
 * the note instead of beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteZone(
    metrics: FrameMetrics?,
    session: SessionUiState,
    targetNote: Note?,
    baselineDb: Double?,
    onTapNote: (Note?) -> Unit,
    onLongPressArc: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val m = metrics
    val voiced = m?.voiced == true
    val score = m?.score ?: 0.0
    val glowMax = if (session.active && !session.paused) 0.32f else 0.12f
    val glow = (score / 0.7).coerceIn(0.0, 1.0).toFloat() * glowMax

    val arcValue = if (m != null && voiced && !m.ringRatioNorm.isNaN() && baselineDb != null) formatDb(m.ringRatioNorm - baselineDb) + " dB" else "—"
    val arcModifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressArc() }) }

    Box(modifier.background(c.panel)) {
        Box(Modifier.matchParentSize().background(c.ok.copy(alpha = glow)))
        Column(Modifier.fillMaxWidth()) {
            ZoneHeader(
                left = {
                    Label(stringResource(R.string.zone_note))
                    Spacer(Modifier.width(8.dp))
                    Badge(if (targetNote != null) stringResource(R.string.badge_target) else stringResource(R.string.badge_live))
                },
                right = {
                    Label(
                        if (targetNote != null) stringResource(R.string.target_prefix) + " " + NoteNames.label(targetNote).joined
                        else stringResource(R.string.tap_to_pin),
                    )
                },
            )
            val noteBlock: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.pointerInput(m?.note) { detectTapGestures(onTap = { onTapNote(if (voiced) m?.note else null) }) }) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.Bottom,
                        itemVerticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            if (voiced) NoteNames.ruShort(m!!.note) else "—",
                            style = if (compact) t.note.copy(fontSize = 66.sp) else t.note,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        if (voiced) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(NoteNames.en(m!!.note), style = t.noteEn, color = c.dim, modifier = Modifier.padding(bottom = 6.dp))
                                Text(NoteNames.cents(m.cents), style = t.cents, color = centsColor(m.cents), modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                    }
                    CentsScale(cents = if (voiced) m!!.cents else Double.NaN, Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(subLine(m, targetNote), style = t.sub, color = c.dim, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp)) {
                    noteBlock(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    CupolaArc(ring = m?.ring ?: 0.0, valueText = arcValue, modifier = arcModifier.width(180.dp).align(Alignment.CenterHorizontally))
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    noteBlock(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    CupolaArc(ring = m?.ring ?: 0.0, valueText = arcValue, modifier = arcModifier.width(150.dp))
                }
            }
            HintRow(session.hint, Modifier.padding(horizontal = CupolaDimens.paddingH).padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun subLine(m: FrameMetrics?, target: Note?): String {
    if (m == null || !m.voiced) return stringResource(R.string.sub_silence)
    val parts = mutableListOf<String>()
    parts += formatHz(m.f0Hz) + " " + stringResource(R.string.unit_hz)
    parts += stringResource(R.string.overtones_n, m.overtoneCount)
    val v = m.vibrato
    parts += when (v.kind) {
        VibratoKind.VIBRATO -> stringResource(R.string.vibrato_fmt, v.rateHz, v.extentCents)
        VibratoKind.WOBBLE -> stringResource(R.string.vibrato_wobble)
        VibratoKind.TREMOLO -> stringResource(R.string.vibrato_tremolo)
        VibratoKind.STRAIGHT -> stringResource(R.string.vibrato_straight)
        VibratoKind.NONE -> "…"
    }
    if (target != null) parts += stringResource(R.string.target_prefix) + " " + NoteNames.ruShort(target)
    return parts.joinToString(" · ")
}

/** −50…+50 ¢ scale with the ±10 ok zone, ±25 ticks and a pin coloured by hit class. */
@Composable
private fun CentsScale(cents: Double, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    val pinColor = centsColor(cents)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2
        val bar = 2.dp.toPx()
        drawRect(c.line2, topLeft = Offset(0f, midY - bar / 2), size = Size(w, bar))
        drawRect(c.ok.copy(alpha = 0.25f), topLeft = Offset(w * 0.4f, 4.dp.toPx()), size = Size(w * 0.2f, h - 8.dp.toPx()))
        drawRect(c.dim, topLeft = Offset(w / 2 - 1f, 0f), size = Size(2f, h))
        for (x in listOf(0.25f, 0.75f)) drawRect(c.line2, topLeft = Offset(w * x, 5.dp.toPx()), size = Size(1.5f, h - 10.dp.toPx()))
        if (!cents.isNaN()) {
            val x = w * (0.5f + (cents.coerceIn(-50.0, 50.0) / 100.0).toFloat())
            val r = 8.dp.toPx()
            drawCircle(c.panel, radius = r + 2.dp.toPx(), center = Offset(x, midY))
            drawCircle(pinColor, radius = r, center = Offset(x, midY))
        }
    }
}

/** Half-circle «купол»: track in panel2, gold sweep ∝ ring, value underneath. */
@Composable
private fun CupolaArc(ring: Double, valueText: String, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(stringResource(R.string.cupola))
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val stroke = 10.dp.toPx()
            val d = min(size.width, size.height * 2) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2)
            val arcSize = Size(d, d)
            drawArc(c.panel2, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (ring > 0.005) {
                drawArc(c.gold, startAngle = 180f, sweepAngle = (180 * ring).toFloat(), useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(valueText, style = t.ringValue, color = c.goldInk, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Reserved fixed-height row: never shifts the layout when a hint appears. */
@Composable
private fun HintRow(hint: HintKey?, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    Row(modifier.fillMaxWidth().height(CupolaDimens.hintRowHeight), verticalAlignment = Alignment.CenterVertically) {
        if (hint != null) {
            val good = hint == HintKey.GOOD
            Box(Modifier.size(CupolaDimens.hintDot).background(if (good) c.ok else c.warn, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when (hint) {
                        HintKey.PUSHED -> R.string.hint_pushed
                        HintKey.DRIFT -> R.string.hint_drift
                        HintKey.GOOD -> R.string.hint_good
                    },
                ),
                style = CupolaTheme.type.hint,
                color = c.mut,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
