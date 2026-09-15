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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

/** Columns on screen: 8 s at 10 ms per column. */
private const val VISIBLE_COLUMNS = 800

/**
 * Zone ③ «Спектрограмма» (SPEC §15.3, T-053). The bitmap is a ring of [VISIBLE_COLUMNS]
 * columns updated incrementally from [SpectrogramHistory] every display frame; axes,
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
    modifier: Modifier = Modifier,
) {
    val c = CupolaTheme.colors
    val colormap = CupolaTheme.colormap
    val measurer = rememberTextMeasurer()
    val axisStyle = CupolaTheme.type.axis
    val renderer = remember(history, colormap) { SpectrogramRenderer(history, colormap) }
    val bandLabel = stringResource(R.string.cupola)
    val thresholds = LocalCentsThresholds.current

    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { tick.longValue = it }
    }

    Column(modifier.background(c.panel)) {
        ZoneHeader(
            left = {
                Label(stringResource(R.string.zone_spectrogram))
                Spacer(Modifier.width(8.dp))
                if (paused) Badge(stringResource(R.string.badge_paused)) else Badge(stringResource(R.string.badge_8s))
            },
            right = { Label(stringResource(R.string.spectrogram_axis_hint)) },
        )
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                tick.longValue // subscribe to display frames
                val gutterL = 40.dp.toPx()
                val gutterR = 26.dp.toPx()
                val gutterB = 16.dp.toPx()
                val plotW = size.width - gutterL - gutterR
                val plotH = size.height - gutterB
                if (plotW <= 0 || plotH <= 0) return@Canvas

                val end = viewEnd ?: history.head
                val image = renderer.render(end)
                val s = renderer.splitAt(end)

                // spectrogram image: two segments of the ring
                val colW = plotW / VISIBLE_COLUMNS
                clipRect(gutterL, 0f, gutterL + plotW, plotH) {
                    val firstLen = VISIBLE_COLUMNS - s
                    if (firstLen > 0) {
                        drawImage(
                            image,
                            srcOffset = IntOffset(s, 0), srcSize = IntSize(firstLen, history.rows),
                            dstOffset = IntOffset(gutterL.toInt(), 0), dstSize = IntSize((firstLen * colW).toInt() + 1, plotH.toInt()),
                        )
                    }
                    if (s > 0) {
                        drawImage(
                            image,
                            srcOffset = IntOffset(0, 0), srcSize = IntSize(s, history.rows),
                            dstOffset = IntOffset((gutterL + firstLen * colW).toInt(), 0), dstSize = IntSize((s * colW).toInt() + 1, plotH.toInt()),
                        )
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
                        while (k * f < history.fMax) {
                            val y = history.yFraction(k * f) * plotH
                            val col = if (k == 1) c.violet.copy(alpha = 0.9f) else c.violet.copy(alpha = 0.3f)
                            drawLine(col, Offset(gutterL, y), Offset(gutterL + plotW, y), strokeWidth = if (k == 1) 1.5.dp.toPx() else 1f)
                            k++
                        }
                    }
                    // f0 trace
                    drawTrace(history, end, gutterL, colW, plotH, c, thresholds)
                }
                // frequency axis
                for (hz in listOf(100, 200, 400, 800, 1600, 3200, 6400)) {
                    val y = history.yFraction(hz.toDouble()) * plotH
                    drawLine(c.line, Offset(gutterL - 3.dp.toPx(), y), Offset(gutterL, y), strokeWidth = 1f)
                    val label = formatKHz(hz)
                    val m = measurer.measure(label, axisStyle)
                    drawLabel(measurer, label, Offset(gutterL - 6.dp.toPx() - m.size.width, y - m.size.height / 2), axisStyle.copy(color = c.dim))
                }
                // time axis
                for (sec in 0..8 step 2) {
                    val x = gutterL + plotW * (1 - sec / 8f)
                    drawLine(c.line, Offset(x, plotH), Offset(x, plotH + 3.dp.toPx()), strokeWidth = 1f)
                    val label = if (sec == 0) "0" else "−$sec"
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

private fun DrawScope.drawTrace(history: SpectrogramHistory, end: Long, x0: Float, colW: Float, plotH: Float, c: ru.dvedev.me.cupola.ui.theme.CupolaColors, th: CentsThresholds) {
    val start = (end - VISIBLE_COLUMNS).coerceAtLeast(0)
    var prevX = Float.NaN
    var prevY = Float.NaN
    var prevF = 0f
    var col = start
    while (col < end) {
        val s = history.slot(col)
        val f = history.f0Hz[s]
        val x = x0 + (col - (end - VISIBLE_COLUMNS)) * colW
        if (f > 0f) {
            val y = history.yFraction(f.toDouble()) * plotH
            val cents = history.cents[s]
            val color = when {
                cents.isNaN() -> c.dim
                abs(cents) <= th.ok -> c.ok
                abs(cents) <= th.warn -> c.warn
                else -> c.bad
            }
            // an octave jump between neighbouring frames is a detector slip, not a glide: break the line
            val jump = prevF > 0f && (f / prevF > 1.25f || prevF / f > 1.25f)
            if (!prevX.isNaN() && !jump) drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2.dp.toPx())
            prevX = x
            prevY = y
            prevF = f
        } else {
            prevX = Float.NaN
            prevF = 0f
        }
        col++
    }
}

/** Owns the ring bitmap and copies new history columns into it. */
private class SpectrogramRenderer(private val history: SpectrogramHistory, private val colormap: SpectrogramColormap) {
    private val bitmap: Bitmap = Bitmap.createBitmap(VISIBLE_COLUMNS, history.rows, Bitmap.Config.ARGB_8888)
    private val image: ImageBitmap = bitmap.asImageBitmap()
    private val column = IntArray(history.rows)
    private var renderedEnd = -1L

    init {
        bitmap.eraseColor(colormap.lut[0])
    }

    fun splitAt(end: Long): Int = ((end % VISIBLE_COLUMNS).toInt())

    fun render(end: Long): ImageBitmap {
        val from = if (renderedEnd < 0 || end < renderedEnd || end - renderedEnd > VISIBLE_COLUMNS) end - VISIBLE_COLUMNS else renderedEnd
        var col = from.coerceAtLeast(0)
        while (col < end) {
            copyColumn(col)
            col++
        }
        renderedEnd = end
        return image
    }

    private fun copyColumn(col: Long) {
        val s = history.slot(col)
        val base = s * history.rows
        val lut = colormap.lut
        for (r in 0 until history.rows) column[r] = lut[history.levels[base + r].toInt() and 0xFF]
        bitmap.setPixels(column, 0, 1, (col % VISIBLE_COLUMNS).toInt(), 0, 1, history.rows)
    }
}
