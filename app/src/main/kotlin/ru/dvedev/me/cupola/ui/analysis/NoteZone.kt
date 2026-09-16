package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.analysis.PointsEvent
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
import ru.dvedev.me.cupola.analysis.DisplayNote
import ru.dvedev.me.cupola.analysis.HintKey
import ru.dvedev.me.cupola.analysis.SessionUiState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import ru.dvedev.me.cupola.dsp.score.Gate
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.notation.NoteNames
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.min

/**
 * Zone ② «Нота» (SPEC §15.3): the note, cents scale, sub-line, cupola arc, reserved hint
 * row and the only green on the screen. Tap pins the current note as the target;  [compact] (landscape column) stacks
 * the arc under the note instead of beside it.
 */
@Composable
fun NoteZone(
    metrics: FrameMetrics?,
    display: DisplayNote,
    session: SessionUiState,
    targetNote: Note?,
    notation: NotationMode,
    accidentals: Accidentals,
    hintsEnabled: Boolean,
    pointsAnimation: Boolean,
    onTapNote: (Note?) -> Unit,
    onLongPressArc: () -> Unit,
    /** «Дать тон» for the pinned note (owner 2026‑09‑16); shown only while a target is pinned. */
    onGiveTone: () -> Unit = {},
    /** Opens the note picker (owner 2026‑09‑16): the small pill left of «Дать тон» shows the pinned note or ♪. */
    onPickNote: () -> Unit = {},
    /** The microphone is delivering silence (busy or silenced by the system). */
    inputSilent: Boolean = false,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val m = metrics
    val voiced = display.voiced
    val score = m?.score ?: 0.0
    val glowMax = if (session.active && !session.paused) 0.32f else 0.12f
    val glow = (score / 0.7).coerceIn(0.0, 1.0).toFloat() * glowMax

    // the arc shows the cupola indicator (owner decision 2026‑09‑15): share of the voice
    // energy in the band × the hump it makes over its flanks, loudness-independent
    val arcValue = "%.0f %%".format(if (display.ringSharePct.isNaN()) 0.0 else display.ringSharePct)
    val arcSub = formatDb(if (display.humpDb.isNaN()) 0.0 else display.humpDb) + " dB"

    val arcFill = if (m != null && m.voice) display.ring.coerceIn(0.0, 1.0) else 0.0
    val arcCounted = display.counted
    val arcModifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressArc() }) }

    Box(modifier.background(c.panel)) {
        Box(Modifier.matchParentSize().background(c.ok.copy(alpha = glow)))
        Column(Modifier.fillMaxWidth()) {
            ZoneHeader(
                collapsed = if (onToggle != null) collapsed else null,
                onToggle = onToggle,
                left = { Label(stringResource(R.string.zone_note)) },
                right = {
                    // owner 2026‑09‑16: no flickering «не считается…» / «тап — закрепить» here — only the
                    // pinned target (and the folded note, and a dead microphone); during a game its
                    // time and points follow on the right
                    val thresholds = LocalCentsThresholds.current
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        // folded zone (owner 2026‑09‑16): the note itself, ▲/▼ when sharp/flat, green when in tune
                        collapsed && voiced -> {
                            val name = if (notation == NotationMode.EN) NoteNames.en(display.note, accidentals) else NoteNames.ruShort(display.note, accidentals) + " · " + NoteNames.en(display.note, accidentals)
                            val arrow = when {
                                display.cents > thresholds.ok -> " ▲"
                                display.cents < -thresholds.ok -> " ▼"
                                else -> ""
                            }
                            // fixed box: the ▲/▼ glyph comes from a taller fallback font and would resize the header
                            Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(name + arrow, style = t.stats, color = centsColor(display.cents), maxLines = 1, softWrap = false)
                            }
                        }
                        collapsed -> Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterEnd) { Text("—", style = t.stats, color = c.dim, maxLines = 1) }
                        inputSilent -> Label(stringResource(R.string.mic_silent), color = c.warn)
                        targetNote != null -> Label(stringResource(R.string.target_prefix) + " " + NoteNames.label(targetNote, notation, accidentals).joined)
                    }
                    if (session.active) {
                        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                            Text(formatTime(session.elapsedSec), style = t.label.copy(fontSize = t.label.fontSize * 1.15f), color = c.mut, maxLines = 1)
                        }
                        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                            Text(session.points.toString(), style = t.stats.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.violetInk, maxLines = 1)
                        }
                    }
                    }
                },
            )
            if (collapsed) return@Column
            val noteBlock: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.pointerInput(display.note) { detectTapGestures(onTap = { onTapNote(if (voiced) display.note else null) }) }) {
                    // two fixed rows: the note alone, then scientific name + cents — nothing ever wraps or shifts
                    // owner 2026‑09‑16: the 84 sp name clipped at the top on phones — smaller, in the same rows
                    val noteStyle = if (compact) t.note.copy(fontSize = 58.sp) else t.note.copy(fontSize = 70.sp)
                    val headline = when {
                        !voiced -> "—"
                        notation == NotationMode.EN -> NoteNames.en(display.note, accidentals)
                        else -> NoteNames.ruShort(display.note, accidentals)
                    }
                    Text(
                        headline,
                        style = noteStyle,
                        color = if (display.holding) c.mut else c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.height(if (compact) 68.dp else 84.dp),
                    )
                    CentsRow(voiced = voiced, showName = voiced && notation == NotationMode.BOTH, name = NoteNames.en(display.note, accidentals), cents = display.cents)
                    val pinCents by animateFloatAsState(targetValue = if (voiced) display.cents.toFloat() else 0f, animationSpec = tween(40, easing = androidx.compose.animation.core.LinearEasing), label = "pin")
                    CentsScale(cents = if (voiced) pinCents.toDouble() else Double.NaN, Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(subLine(m, display, targetNote, notation, accidentals), style = t.sub, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp))
                }
            }
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp)) {
                    noteBlock(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(180.dp).align(Alignment.CenterHorizontally)) {
                        CupolaArc(ring = arcFill, counted = arcCounted, valueText = arcValue, subText = arcSub, modifier = arcModifier.fillMaxWidth(), targetNote = targetNote, onGiveTone = onGiveTone, onPickNote = onPickNote)
                        if (pointsAnimation) PointsBurst(session.lastPoints, Modifier.matchParentSize())
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    noteBlock(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(172.dp)) {
                        CupolaArc(ring = arcFill, counted = arcCounted, valueText = arcValue, subText = arcSub, modifier = arcModifier.fillMaxWidth(), targetNote = targetNote, onGiveTone = onGiveTone, onPickNote = onPickNote)
                        if (pointsAnimation) PointsBurst(session.lastPoints, Modifier.matchParentSize())
                    }
                }
            }
            HintRow(if (hintsEnabled) session.hint else null, Modifier.padding(horizontal = CupolaDimens.paddingH).padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun subLine(m: FrameMetrics?, d: DisplayNote, target: Note?, notation: NotationMode, accidentals: Accidentals): String {
    if (m == null || !d.voiced) return stringResource(R.string.sub_silence)
    val parts = mutableListOf<String>()
    parts += formatHz(d.f0Hz) + " " + stringResource(R.string.unit_hz)
    // overall voice level above the noise, to compare with the cupola's dB (owner 2026‑09‑16);
    // overtones and the target left this line — the graphs and the header show them
    if (!d.voiceDb.isNaN()) parts += "%.0f dB".format(d.voiceDb).replace("-", "−")
    val v = m.vibrato
    parts += when (v.kind) {
        VibratoKind.VIBRATO -> stringResource(R.string.vibrato_fmt, v.rateHz, v.extentCents)
        VibratoKind.WOBBLE -> stringResource(R.string.vibrato_wobble)
        VibratoKind.TREMOLO -> stringResource(R.string.vibrato_tremolo)
        VibratoKind.STRAIGHT -> stringResource(R.string.vibrato_straight)
        VibratoKind.NONE -> "…"
    }
    return parts.joinToString(" · ")
}

/**
 * Scientific name + cents in fixed places (owner 2026‑09‑16: the ¢ drifted with the width of
 * the digits): the name at the left edge, the number centred over the middle of the cents scale
 * in a box sized for the widest value, the ¢ right after it. When the column is too narrow for a
 * centred group, the group sits right after a fixed-width name box instead — still static.
 */
@Composable
private fun CentsRow(voiced: Boolean, showName: Boolean, name: String, cents: Double) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
        val wide = maxWidth >= 300.dp
        val nameStyle = if (wide) t.noteEn else t.noteEn.copy(fontSize = t.noteEn.fontSize * 0.75f)
        val centsStyle = if (wide) t.cents else t.cents.copy(fontSize = t.cents.fontSize * 0.75f)
        val numW = with(density) { measurer.measure("−50", centsStyle).size.width.toDp() + 4.dp }
        val signW = with(density) { measurer.measure("¢", centsStyle).size.width.toDp() + 6.dp }
        val nameW = with(density) { measurer.measure("G♯8", nameStyle).size.width.toDp() + 8.dp }
        val color = centsColor(cents)
        val number = if (voiced) NoteNames.cents(cents).removeSuffix(" ¢").removeSuffix("¢").trim() else ""
        val group: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number, style = centsStyle, color = color, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(numW))
                Text(if (voiced) "¢" else "", style = centsStyle, color = color, maxLines = 1, modifier = Modifier.width(signW).padding(start = 4.dp))
            }
        }
        if (showName) Text(name, style = nameStyle, color = c.dim, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
        val centred = maxWidth >= nameW * 2 + numW + signW * 2
        if (centred || !showName) {
            // number centred on the scale: an invisible ¢-wide spacer on the left balances the ¢ on the right
            Row(Modifier.align(Alignment.BottomCenter), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(signW))
                group()
            }
        } else {
            Row(Modifier.align(Alignment.BottomStart).padding(start = nameW), verticalAlignment = Alignment.Bottom) { group() }
        }
    }
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

/**
 * Half-circle «купол» (owner decision 2026‑09‑15): the gold fill grows from BOTH ends of
 * the arc towards the apex, ∝ the cupola indicator. When the halves meet ([MET]) the apex
 * flickers and sparks, points flow and the phone vibrates. [counted] = the ring is being
 * earned (voice present, pitch trusted); otherwise the fill is dimmed.
 */
@Composable
private fun CupolaArc(ring: Double, counted: Boolean, valueText: String, subText: String, modifier: Modifier = Modifier, targetNote: Note? = null, onGiveTone: () -> Unit = {}, onPickNote: () -> Unit = {}) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val fill by animateFloatAsState(targetValue = ring.toFloat(), animationSpec = tween(180), label = "ring")
    val met = fill >= MET
    // a clock for the sparkle, running only while the halves have met
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(met) {
        if (!met) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> clock = (now - start) / 1e9f }
    }
    val sparks = remember { List(SPARKS) { i -> Spark(seed = i * 0.618f % 1f, speed = 0.7f + (i % 5) * 0.15f, angle = (i * 137f) % 360f) } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(stringResource(R.string.cupola))
        Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxWidth().height(78.dp)) {
            val stroke = 10.dp.toPx()
            val d = min(size.width, size.height * 2) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2)
            val arcSize = Size(d, d)
            val apex = Offset(size.width / 2, stroke / 2)
            drawArc(c.panel2, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fill > 0.005f) {
                val color = if (counted) c.gold else c.gold.copy(alpha = 0.45f)
                val half = 90f * fill.coerceAtMost(1f)
                // left half: from the left end (180°) clockwise towards the apex (270°)
                drawArc(color, startAngle = 180f, sweepAngle = half, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // right half: from the right end (0°) counter-clockwise towards the apex
                drawArc(color, startAngle = 0f, sweepAngle = -half, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (met && counted) {
                // the meeting point flickers …
                val flicker = 0.55f + 0.45f * kotlin.math.sin(clock * 14f).coerceAtLeast(0f)
                drawCircle(c.gold.copy(alpha = 0.35f * flicker), radius = stroke * 1.6f, center = apex)
                drawCircle(c.panel.copy(alpha = 0.9f * flicker), radius = stroke * 0.45f, center = apex)
                // … and sparks fly out of it
                for (sp in sparks) {
                    val life = ((clock * sp.speed + sp.seed) % 1f)
                    val r = stroke * (0.8f + 2.6f * life)
                    val a = Math.toRadians((sp.angle + clock * 25f).toDouble())
                    val p = Offset(apex.x + (r * kotlin.math.cos(a)).toFloat(), apex.y - (r * kotlin.math.sin(a)).toFloat().coerceAtLeast(-apex.y) * 0.9f)
                    drawCircle((if (life < 0.5f) c.gold else c.ok).copy(alpha = (1f - life) * 0.9f), radius = stroke * 0.18f * (1.5f - life), center = p)
                }
            }
        }
        // the share sits inside the arch (owner 2026‑09‑16), the hump in dB under it
        Text(valueText, style = t.ringValue, color = if (met && counted) c.ok else c.goldInk, maxLines = 1)
        }
        Text(subText, style = t.stats, color = c.goldInk, maxLines = 1, modifier = Modifier.height(22.dp).padding(top = 2.dp))
        // under the readout: the pinned note (or ♪ → picker) and «Дать тон», which needs a pinned note
        // the arc column is sized so «A♯4 · Дать тон» fits: anything drawn outside a node's bounds is not repainted reliably
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (targetNote != null) {
                PillButton(NoteNames.en(targetNote), onClick = onPickNote, style = PillStyle.Outline, compact = true, active = true)
            } else {
                // a drawn sharp: the ♯ glyph comes from a fallback font and sat off-centre
                val ink = c.mut
                PillButton("", onClick = onPickNote, style = PillStyle.Outline, compact = true, leading = {
                    // as tall as a line of button text, so both pills in the row have the same height
                    val lineH = with(androidx.compose.ui.platform.LocalDensity.current) { CupolaTheme.type.button.lineHeight.let { if (it.isSp) it.toDp() else CupolaTheme.type.button.fontSize.toDp() * 1.3f } }
                    Canvas(Modifier.width(14.dp).height(lineH)) {
                        val w = size.width
                        val g = 14.dp.toPx()
                        val top = (size.height - g) / 2
                        val sw = 1.6.dp.toPx()
                        drawLine(ink, Offset(w * 0.38f, top + g * 0.05f), Offset(w * 0.30f, top + g * 0.95f), strokeWidth = sw)
                        drawLine(ink, Offset(w * 0.70f, top + g * 0.05f), Offset(w * 0.62f, top + g * 0.95f), strokeWidth = sw)
                        drawLine(ink, Offset(w * 0.08f, top + g * 0.40f), Offset(w * 0.92f, top + g * 0.30f), strokeWidth = sw * 1.3f)
                        drawLine(ink, Offset(w * 0.08f, top + g * 0.72f), Offset(w * 0.92f, top + g * 0.62f), strokeWidth = sw * 1.3f)
                    }
                })
            }
            // Muted (panel fill + darker line): the Outline border is invisible on the cream panel
            PillButton(stringResource(R.string.action_give_tone), onClick = onGiveTone, style = PillStyle.Muted, compact = true, enabled = targetNote != null)
        }
    }
}

/** One spark of the meeting animation: [seed] phases it, [speed] in lives per second, [angle] degrees. */
private class Spark(val seed: Float, val speed: Float, val angle: Float)

/** The cupola halves meet at this fill: sparkle, points, vibration. Mirrors HapticsController.MET. */
private const val MET = 0.9f
private const val SPARKS = 14

/** A flying dot of the points animation; [t] runs 0→1 over its life. */
private class Dot(val x: Float, val size: Float, val green: Boolean, val drift: Float) {
    var t by mutableFloatStateOf(0f)
}

/**
 * Gold dots rising out of the cupola arc on every points award (SPEC §15.3): one per
 * point of the portion, green at score ≥ 0.85, size ∝ ring. Purely decorative and
 * switchable in settings.
 */
@Composable
private fun PointsBurst(event: PointsEvent?, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    val dots = remember { mutableStateListOf<Dot>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(event?.id) {
        val e = event ?: return@LaunchedEffect
        repeat(e.portion.coerceIn(1, 3)) { i ->
            val dot = Dot(x = (i - (e.portion - 1) / 2f) * 0.18f, size = 4f + 5f * e.ring.toFloat(), green = e.green, drift = (i % 2 * 2 - 1) * 0.08f)
            dots += dot
            scope.launch {
                delay(i * 90L)
                animate(0f, 1f, animationSpec = tween(1100, easing = FastOutSlowInEasing)) { v, _ -> dot.t = v }
                dots -= dot
            }
        }
    }
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height * 0.55f
        for (d in dots) {
            val t = d.t
            if (t <= 0f) continue
            val x = cx + (d.x + d.drift * t) * size.width
            val y = cy - t * size.height * 0.9f
            val alpha = (1f - t).coerceIn(0f, 1f)
            drawCircle((if (d.green) c.ok else c.gold).copy(alpha = alpha), radius = d.size.dp.toPx() * (1f + 0.3f * t), center = Offset(x, y))
        }
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
