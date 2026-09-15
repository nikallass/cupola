package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.analysis.SpectrumSnapshot
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

private const val F_MIN = 80.0
private const val F_MAX = 8000.0
private const val DB_SPAN = 70f
/** Horizontal resolution of the curve, px per point; every pixel is too much for the E11 GPU. */
private const val PX_STEP = 2

/**
 * Zone ④ «Спектр» (SPEC §15.3, T-054): log-x spectrum with gold fill, cupola band, dashed
 * harmonic envelope, harmonic numbers, dashed noise floor.
 */
@Composable
fun SpectrumZone(
    snapshot: SpectrumSnapshot,
    band: RingBand,
    ringNormDb: Double?,
    baselineDb: Double?,
    paused: Boolean,
    /** Running maximum of the spectrogram normalisation; the dB axis follows it. */
    topDb: () -> Float,
    logScale: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = CupolaTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 128) // ~25 distinct labels per frame; the default 8 thrashes
    val axisStyle = CupolaTheme.type.axis
    val tick = remember { mutableLongStateOf(0L) }
    // redraw at a third of the display rate (20 Hz): the spectrum changes 100×/s anyway and the path
    // tessellation is the most expensive thing on screen (T-070 measurements)
    LaunchedEffect(paused) {
        var n = 0
        if (!paused) while (true) withFrameNanos { if (++n % 3 == 0) tick.longValue = it }
    }
    val curve = remember { Path() }
    val fill = remember { Path() }
    val envelope = remember { Path() }
    val floor = remember { Path() }

    Column(modifier.background(c.panel)) {
        ZoneHeader(
            left = {
                Label(stringResource(R.string.zone_spectrum))
                Spacer(Modifier.width(8.dp))
                Badge(stringResource(R.string.badge_db))
            },
            right = {
                Label(
                    when {
                        baselineDb == null -> stringResource(R.string.no_calibration)
                        ringNormDb == null || ringNormDb.isNaN() -> stringResource(R.string.cupola) + " —"
                        else -> stringResource(R.string.cupola_vs_baseline, formatDb(ringNormDb - baselineDb))
                    },
                )
            },
        )
        // Offscreen layer: HWUI keeps the rendered spectrum as a texture and re-executes the
        // heavy path drawing only when the canvas is invalidated (20 Hz), not on every vsync
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            tick.longValue
            val gutterL = 40.dp.toPx()
            val gutterR = 12.dp.toPx()
            val gutterB = 16.dp.toPx()
            val gutterT = 14.dp.toPx()
            val plotW = size.width - gutterL - gutterR
            val plotH = size.height - gutterB - gutterT
            if (plotW <= 0 || plotH <= 0) return@Canvas
            val logSpan = ln(F_MAX / F_MIN)
            val px = plotW.toInt()
            // axis: top at the next 10 dB above the running maximum, 70 dB down
            val top = (kotlin.math.ceil(topDb() / 10f) * 10f + 5f).coerceIn(-45f, 5f)
            val bottom = top - DB_SPAN
            fun xOf(hz: Double): Float = if (logScale) gutterL + (ln(hz / F_MIN) / logSpan).toFloat() * plotW else gutterL + ((hz - F_MIN) / (F_MAX - F_MIN)).toFloat() * plotW
            fun hzAt(i: Int): Double = if (logScale) F_MIN * (F_MAX / F_MIN).pow(i.toDouble() / px) else F_MIN + (F_MAX - F_MIN) * i.toDouble() / px
            fun yOf(db: Float): Float = gutterT + (top - db.coerceIn(bottom, top)) / DB_SPAN * plotH

            // grid
            var gridDb = (kotlin.math.floor(top / 10f) * 10f).toInt()
            while (gridDb > bottom) {
                val db = gridDb
                gridDb -= 10
                val y = yOf(db.toFloat())
                drawLine(c.line, Offset(gutterL, y), Offset(gutterL + plotW, y), strokeWidth = 1f)
                val label = db.toString()
                val m = measurer.measure(label, axisStyle)
                drawLabel(measurer, label, Offset(gutterL - 6.dp.toPx() - m.size.width, y - m.size.height / 2), axisStyle.copy(color = c.dim))
            }
            for (hz in if (logScale) listOf(100, 200, 400, 800, 1600, 3200, 6400) else listOf(1000, 2000, 3000, 4000, 5000, 6000, 7000)) {
                val x = xOf(hz.toDouble())
                drawLine(c.line, Offset(x, gutterT + plotH), Offset(x, gutterT + plotH + 3.dp.toPx()), strokeWidth = 1f)
                val label = formatKHz(hz)
                val m = measurer.measure(label, axisStyle)
                drawLabel(measurer, label, Offset(x - m.size.width / 2, gutterT + plotH + 3.dp.toPx()), axisStyle.copy(color = c.dim))
            }
            // cupola band
            val bx0 = xOf(band.loHz)
            val bx1 = xOf(band.hiHz)
            drawRect(c.gold.copy(alpha = 0.10f), topLeft = Offset(bx0, gutterT), size = Size(bx1 - bx0, plotH))

            val frame = snapshot.latest ?: return@Canvas
            val binHz = frame.binHz
            if (binHz <= 0) return@Canvas
            val db = frame.db
            // curve: one point per pixel column — max over the bins that map into it where
            // bins are dense, linear interpolation between bins where pixels are denser
            curve.reset()
            fill.reset()
            var prevBin = -1
            var started = false
            for (i in 0..px step PX_STEP) {
                val hz = hzAt(i)
                val fbin = (hz / binHz).toFloat().coerceIn(0f, (db.size - 1).toFloat())
                val bin = fbin.toInt()
                var v = if (bin + 1 < db.size) {
                    val t = fbin - bin
                    db[bin] * (1 - t) + db[bin + 1] * t
                } else {
                    db[bin]
                }
                if (prevBin >= 0 && bin > prevBin + 1) for (k in prevBin + 1..bin) v = max(v, db[k])
                prevBin = bin
                val x = gutterL + i
                val y = yOf(v)
                if (!started) {
                    curve.moveTo(x, y); fill.moveTo(x, gutterT + plotH); fill.lineTo(x, y); started = true
                } else {
                    curve.lineTo(x, y); fill.lineTo(x, y)
                }
            }
            fill.lineTo(gutterL + plotW, gutterT + plotH)
            fill.close()
            clipRect(gutterL, gutterT, gutterL + plotW, gutterT + plotH) {
                drawPath(fill, c.gold.copy(alpha = 0.22f))
                drawPath(curve, c.gold, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Bevel, cap = StrokeCap.Butt))
                // noise floor: thin solid line (a dash effect costs more GPU than the whole curve)
                floor.reset()
                var fStarted = false
                var i = 0
                while (i <= px) {
                    val hz = hzAt(i)
                    val bin = (hz / binHz).toInt().coerceIn(0, db.size - 1)
                    val y = yOf(frame.floorDb[bin])
                    if (!fStarted) { floor.moveTo(gutterL + i, y); fStarted = true } else floor.lineTo(gutterL + i, y)
                    i += 6
                }
                drawPath(floor, c.dim.copy(alpha = 0.45f), style = Stroke(1f))
                // harmonic envelope + numbers
                val hs = frame.harmonics.filter { it.audible && it.hz in F_MIN..F_MAX }
                if (hs.size >= 2) {
                    envelope.reset()
                    hs.forEachIndexed { idx, h ->
                        val x = xOf(h.hz)
                        val y = yOf(h.levelDb.toFloat()) - 3.dp.toPx()
                        if (idx == 0) envelope.moveTo(x, y) else envelope.lineTo(x, y)
                    }
                    drawPath(envelope, c.mut, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                }
                var lastLabelRight = -1000f
                val gap = 4.dp.toPx()
                for (h in hs) {
                    val x = xOf(h.hz)
                    val y = yOf(h.levelDb.toFloat())
                    drawLine(c.mut, Offset(x, y - 2.dp.toPx()), Offset(x, y - 6.dp.toPx()), strokeWidth = 1f)
                    if (h.k <= 16) {
                        val label = h.k.toString()
                        val m = measurer.measure(label, axisStyle)
                        val left = x - m.size.width / 2
                        if (left - lastLabelRight >= gap) {
                            drawLabel(measurer, label, Offset(left, (y - 8.dp.toPx() - m.size.height).coerceAtLeast(gutterT)), axisStyle.copy(color = c.mut))
                            lastLabelRight = left + m.size.width
                        }
                    }
                }
            }
        }
    }
}
