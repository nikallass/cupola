package ru.dvedev.me.cupola.ui.analysis

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.analysis.SpectrogramHistory
import ru.dvedev.me.cupola.dsp.metrics.Harmonic
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import ru.dvedev.me.cupola.ui.theme.SpectrogramColormap
import kotlin.math.abs

/** Columns kept in the ring bitmap: 30 s at 10 ms per column — the widest time span a pinch can show. */
private const val MAX_COLUMNS = 3000

/**
 * Zone ③ «Спектрограмма» (SPEC §15.3, T-053). The bitmap is a ring of [MAX_COLUMNS] (30 s)
 * columns updated incrementally from [SpectrogramHistory] every display frame; the last
 * `visibleColumns` of them are stretched over the plot (pinch to change); axes,
 * cupola band, f0 trace, target lines and harmonic ticks are drawn on top.
 */
@Composable
fun SpectrogramZone(
    history: SpectrogramHistory,
    band: RingBand,
    targetNote: Note?,
    harmonics: List<Harmonic>,
    paused: Boolean,
    viewEnd: Long?, // when paused: last column to show, else null = live
    onScroll: (columns: Int) -> Unit = {},
    /** Visible time span in columns (10 ms each), 200…3000; a horizontal pinch changes it via [onZoom]. */
    visibleColumns: Int = 800,
    onZoom: (factor: Float) -> Unit = {},
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
    /** Display adjustment of the palette (settings): contrast 50…300 % as a power curve on the level. */
    contrastPct: Int = 100,
    modifier: Modifier = Modifier,
) {
    val c = CupolaTheme.colors
    val baseColormap = CupolaTheme.colormap
    // above 100 % the curve is anchored to the running peak level (quantised so the LUT is not rebuilt every frame)
    val peakLevel = if (contrastPct > 100) (((history.topDb - history.bottomDb) / (history.topDisplayDb - history.bottomDb)) * 20f).toInt() / 20f else 1f
    val colormap = remember(baseColormap, contrastPct, peakLevel) { baseColormap.adjusted(contrastPct, peakLevel) }
    val measurer = rememberTextMeasurer(cacheSize = 128) // ~25 distinct labels per frame; the default 8 thrashes
    val axisStyle = CupolaTheme.type.axis
    // the renderer survives palette changes: a new contrast colours only the columns drawn from now on (owner 2026‑09‑16)
    val renderer = remember(history) { SpectrogramRenderer(history, colormap) }
    renderer.colormap = colormap
    val bandLabel = stringResource(R.string.cupola)
    val thresholds = LocalCentsThresholds.current
    val tracePaths = remember { Array(3) { Path() } }
    val srcRect = remember { android.graphics.Rect() }
    val dstRect = remember { android.graphics.RectF() }
    val bitmapPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }

    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { tick.longValue = it }
    }

    Column(modifier.background(c.panel)) {
        ZoneHeader(
            collapsed = if (onToggle != null) collapsed else null,
            onToggle = onToggle,
            left = {
                Label(stringResource(R.string.zone_spectrogram))
                // only a state worth knowing gets a badge (owner 2026‑09‑16: «8 с» said nothing)
                if (paused) {
                    Spacer(Modifier.width(8.dp))
                    Badge(stringResource(R.string.badge_paused))
                }
            },
        )
        if (collapsed) return@Column
        var plotWidthPx by remember { mutableFloatStateOf(1f) }
        // the gesture detector must survive zoom changes: keyed only on `paused`, it reads the
        // latest span and callbacks through updated state (keying on the span restarted it on
        // every step and cut the pinch off after a few milliseconds)
        val spanNow by rememberUpdatedState(visibleColumns)
        val zoomNow by rememberUpdatedState(onZoom)
        val scrollNow by rememberUpdatedState(onScroll)
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.pointerInput(paused) {
                    // pinch (live and paused) squeezes or stretches the time axis; while paused a drag scrolls back
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) zoomNow(zoom)
                        if (paused && pan.x != 0f) scrollNow((pan.x / (plotWidthPx / spanNow)).toInt())
                    }
                },
            ) {
                tick.longValue // subscribe to display frames
                val gutterL = 40.dp.toPx()
                val gutterR = 26.dp.toPx()
                val gutterB = 16.dp.toPx()
                val plotW = size.width - gutterL - gutterR
                val plotH = size.height - gutterB
                if (plotW <= 0 || plotH <= 0) return@Canvas
                plotWidthPx = plotW

                val end = viewEnd ?: history.head
                val bitmap = renderer.render(end)
                val s = renderer.splitAt(end)

                // spectrogram image: two segments of the ring, drawn through the platform canvas
                // (Compose's drawImage would copy the mutable bitmap into an SkImage every frame)
                val visible = visibleColumns.coerceIn(1, MAX_COLUMNS)
                val colW = plotW / visible
                clipRect(gutterL, 0f, gutterL + plotW, plotH) {
                    drawIntoCanvas { canvas ->
                        // the last `visible` columns of the ring: [a, M) + [0, b) when they wrap, else [a, b)
                        val native = canvas.nativeCanvas
                        val a = Math.floorMod(s - visible, MAX_COLUMNS)
                        val firstLen = if (a < s) visible else MAX_COLUMNS - a
                        srcRect.set(a, 0, if (a < s) s else MAX_COLUMNS, history.rows)
                        dstRect.set(gutterL, 0f, gutterL + firstLen * colW + 1f, plotH)
                        native.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                        if (a >= s && s > 0) {
                            srcRect.set(0, 0, s, history.rows)
                            dstRect.set(gutterL + firstLen * colW, 0f, gutterL + plotW + 1f, plotH)
                            native.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                        }
                    }
                    // cupola band
                    val yHi = history.yFraction(band.hiHz) * plotH
                    val yLo = history.yFraction(band.loHz) * plotH
                    drawRect(c.gold.copy(alpha = 0.10f), topLeft = Offset(gutterL, yHi), size = Size(plotW, yLo - yHi))
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    drawLine(c.gold, Offset(gutterL, yHi), Offset(gutterL + plotW, yHi), strokeWidth = 1f, pathEffect = dash)
                    drawLine(c.gold, Offset(gutterL, yLo), Offset(gutterL + plotW, yLo), strokeWidth = 1f, pathEffect = dash)
                    drawLabel(measurer, bandLabel, Offset(gutterL + 6.dp.toPx(), yHi - 12.dp.toPx()), axisStyle.copy(color = c.goldInk))

                    // target line + harmonics
                    if (targetNote != null) {
                        val f = targetNote.hz()
                        var k = 1
                        while (k * f < history.fMax && k <= 16) { // up to the 16th, like the harmonic numbers
                            val y = history.yFraction(k * f) * plotH
                            // every harmonic of the target equally readable (owner 2026‑09‑16: 1 px at 30 % vanished above k = 1)
                            val col = if (k == 1) c.violet.copy(alpha = 0.95f) else c.violet.copy(alpha = 0.7f)
                            drawLine(col, Offset(gutterL, y), Offset(gutterL + plotW, y), strokeWidth = if (k == 1) 2.dp.toPx() else 1.2.dp.toPx())
                            k++
                        }
                    }
                    // f0 trace
                    drawTrace(history, end, visible, gutterL, colW, plotH, c, thresholds, tracePaths)
                }
                // frequency axis
                val ticks = if (history.logScale) listOf(100, 200, 400, 800, 1600, 3200, 6400) else listOf(1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000)
                for (hz in ticks) {
                    val y = history.yFraction(hz.toDouble()) * plotH
                    drawLine(c.line, Offset(gutterL - 3.dp.toPx(), y), Offset(gutterL, y), strokeWidth = 1f)
                    val label = formatKHz(hz)
                    val m = measurer.measure(label, axisStyle)
                    drawLabel(measurer, label, Offset(gutterL - 6.dp.toPx() - m.size.width, y - m.size.height / 2), axisStyle.copy(color = c.dim))
                }
                // time axis (relative to the live edge; when scrolled back the offset is added)
                val backSec = if (viewEnd != null) ((history.head - viewEnd) * 0.01).toInt() else 0
                val spanSec = visible / 100f
                val step = when { spanSec <= 4f -> 1; spanSec <= 10f -> 2; spanSec <= 20f -> 4; else -> 5 }
                for (sec in 0..spanSec.toInt() step step) {
                    val x = gutterL + plotW * (1 - sec / spanSec)
                    drawLine(c.line, Offset(x, plotH), Offset(x, plotH + 3.dp.toPx()), strokeWidth = 1f)
                    val total = sec + backSec
                    val label = if (total == 0) "0" else "−$total"
                    val m = measurer.measure(label, axisStyle)
                    drawLabel(measurer, label, Offset((x - m.size.width / 2).coerceIn(gutterL, gutterL + plotW - m.size.width), plotH + 3.dp.toPx()), axisStyle.copy(color = c.dim))
                }
                // harmonic ticks at the right edge; labels skip when they would collide
                var lastLabelY = Float.NEGATIVE_INFINITY
                val minGap = 10.dp.toPx()
                for (h in harmonics.sortedByDescending { it.hz }) {
                    if (!h.audible || h.k > 16 || h.hz > history.fMax || h.hz < history.fMin) continue
                    val y = history.yFraction(h.hz) * plotH
                    drawLine(c.mut, Offset(gutterL + plotW, y), Offset(gutterL + plotW + 4.dp.toPx(), y), strokeWidth = 1.5f)
                    if (y - lastLabelY >= minGap) {
                        val m = measurer.measure(h.k.toString(), axisStyle)
                        drawLabel(measurer, h.k.toString(), Offset(gutterL + plotW + 6.dp.toPx(), y - m.size.height / 2), axisStyle.copy(color = c.mut))
                        lastLabelY = y
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTrace(
    history: SpectrogramHistory, end: Long, visible: Int, x0: Float, colW: Float, plotH: Float,
    c: ru.dvedev.me.cupola.ui.theme.CupolaColors, th: CentsThresholds, paths: Array<Path>,
) {
    // one path per hit class (ok / warn / bad) instead of ~800 drawLine calls per frame
    for (p in paths) p.reset()
    val start = (end - visible).coerceAtLeast(0)
    var prevX = Float.NaN
    var prevY = Float.NaN
    var prevF = 0f
    var col = start
    while (col < end) {
        val s = history.slot(col)
        val f = history.f0Hz[s]
        val x = x0 + (col - (end - visible)) * colW
        if (f > 0f) {
            val y = history.yFraction(f.toDouble()) * plotH
            val cents = history.cents[s]
            val cls = when {
                cents.isNaN() -> 0
                abs(cents) <= th.ok -> 0
                abs(cents) <= th.warn -> 1
                else -> 2
            }
            // an octave jump between neighbouring frames is a detector slip, not a glide: break the line
            val jump = prevF > 0f && (f / prevF > 1.25f || prevF / f > 1.25f)
            if (!prevX.isNaN() && !jump) {
                paths[cls].moveTo(prevX, prevY)
                paths[cls].lineTo(x, y)
            }
            prevX = x
            prevY = y
            prevF = f
        } else {
            prevX = Float.NaN
            prevF = 0f
        }
        col++
    }
    val colors = arrayOf(c.ok, c.warn, c.bad)
    val stroke = Stroke(2.dp.toPx())
    for (i in 0..2) drawPath(paths[i], colors[i], style = stroke)
}

/** Owns the ring bitmap and copies new history columns into it. */
private class SpectrogramRenderer(private val history: SpectrogramHistory, @Volatile var colormap: SpectrogramColormap) {
    private val bitmap: Bitmap = Bitmap.createBitmap(MAX_COLUMNS, history.rows, Bitmap.Config.ARGB_8888)
    private val column = IntArray(history.rows)
    private var renderedEnd = -1L

    init {
        bitmap.eraseColor(colormap.lut[0])
    }

    fun splitAt(end: Long): Int = ((end % MAX_COLUMNS).toInt())

    fun render(end: Long): Bitmap {
        val from = if (renderedEnd < 0 || end < renderedEnd || end - renderedEnd > MAX_COLUMNS) end - MAX_COLUMNS else renderedEnd
        var col = from.coerceAtLeast(0)
        while (col < end) {
            copyColumn(col)
            col++
        }
        renderedEnd = end
        return bitmap
    }

    private fun copyColumn(col: Long) {
        val s = history.slot(col)
        val base = s * history.rows
        val lut = colormap.lut
        for (r in 0 until history.rows) column[r] = lut[history.levels[base + r].toInt() and 0xFF]
        bitmap.setPixels(column, 0, 1, (col % MAX_COLUMNS).toInt(), 0, 1, history.rows)
    }
}
