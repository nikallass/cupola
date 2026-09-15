package ru.dvedev.me.cupola.analysis

import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 60-second ring of spectrogram columns, written on the analysis thread once per hop
 * (SPEC §15.3, T-053). Rows are log-spaced from [fMax] (row 0, top) down to [fMin];
 * levels are quantised to 0..255 over [dynamicRangeDb] below a running maximum of the
 * last [normSeconds], so the picture stays readable at any volume.
 */
class SpectrogramHistory(
    val rows: Int = 320,
    val columns: Int = 6000,
    val fMin: Double = 80.0,
    val fMax: Double = 8000.0,
    val dynamicRangeDb: Float = 60f,
    normSeconds: Double = 3.0,
    hopSeconds: Double = 0.01,
) : FrameListener {
    val levels = ByteArray(rows * columns)
    val f0Hz = FloatArray(columns)
    val cents = FloatArray(columns) { Float.NaN }

    /** Total columns written; column `i` lives at slot `i % columns`. */
    @Volatile var head: Long = 0
        private set

    /** Current normalisation top in dB (levels 255 ↔ this). */
    @Volatile var topDb: Float = -30f
        private set

    /** Log-spaced rows (default) or linear; switching clears the history (settings → «Шкала»). */
    @Volatile var logScale: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            binHz = 0.0 // forces prepare() on the next frame
            clear()
        }

    fun clear() {
        levels.fill(0)
        f0Hz.fill(0f)
        cents.fill(Float.NaN)
        head = 0
    }

    private val normFrames = (normSeconds / hopSeconds).roundToInt().coerceAtLeast(1)
    private val peaks = FloatArray(normFrames) { -120f }
    private var peakIdx = 0
    private var binHz = 0.0
    private var rowLo = IntArray(0)
    private var rowHi = IntArray(0)
    private var rowCenter = FloatArray(0)
    private var binMin = 0
    private var binMax = 0
    private val logSpan = ln(fMax / fMin)

    fun slot(column: Long): Int = (column % columns).toInt()

    /** Frequency at the centre of a row. */
    fun rowHz(row: Int): Double = hzAtFraction((row + 0.5) / rows)

    /** Frequency at a vertical fraction (0 = top = fMax, 1 = bottom = fMin). */
    fun hzAtFraction(f: Double): Double =
        if (logScale) fMin * (fMax / fMin).pow(1.0 - f) else fMax - (fMax - fMin) * f

    /** Vertical position (0 = top) of a frequency as a fraction of the plot height. */
    fun yFraction(hz: Double): Float =
        if (logScale) (1.0 - ln(hz / fMin) / logSpan).toFloat() else ((fMax - hz) / (fMax - fMin)).toFloat()

    override fun onFrame(metrics: FrameMetrics, spectrum: PowerSpectrum) {
        if (binHz != spectrum.binHz) prepare(spectrum)
        val db = spectrum.db
        var peak = -140.0
        for (k in binMin..binMax) if (db[k] > peak) peak = db[k]
        peaks[peakIdx] = peak.toFloat()
        peakIdx = (peakIdx + 1) % normFrames
        var top = -140f
        for (p in peaks) if (p > top) top = p
        top = max(top, FLOOR_TOP_DB)
        topDb = top
        val bottom = top - dynamicRangeDb
        val scale = 255f / dynamicRangeDb

        val s = slot(head)
        val base = s * rows
        for (r in 0 until rows) {
            val lo = rowLo[r]
            val hi = rowHi[r]
            val v = if (hi > lo) {
                var m = -200.0
                for (k in lo..hi) if (db[k] > m) m = db[k]
                m
            } else {
                val c = rowCenter[r]
                val k = floor(c).toInt().coerceIn(0, db.size - 2)
                val t = c - k
                db[k] * (1 - t) + db[k + 1] * t
            }
            val level = ((v - bottom) * scale).toInt().coerceIn(0, 255)
            levels[base + r] = level.toByte()
        }
        f0Hz[s] = if (metrics.voiced) metrics.f0Hz.toFloat() else 0f
        cents[s] = if (metrics.voiced) metrics.cents.toFloat() else Float.NaN
        head++
    }

    private fun prepare(spectrum: PowerSpectrum) {
        binHz = spectrum.binHz
        rowLo = IntArray(rows)
        rowHi = IntArray(rows)
        rowCenter = FloatArray(rows)
        val last = spectrum.bins - 1
        for (r in 0 until rows) {
            val hiHz = hzAtFraction(r.toDouble() / rows)
            val loHz = hzAtFraction((r + 1.0) / rows)
            rowLo[r] = ceil(loHz / binHz).toInt().coerceIn(0, last)
            rowHi[r] = floor(hiHz / binHz).toInt().coerceIn(0, last)
            rowCenter[r] = (rowHz(r) / binHz).toFloat().coerceIn(0f, last.toFloat())
        }
        binMin = ceil(fMin / binHz).toInt().coerceIn(0, last)
        binMax = floor(fMax / binHz).toInt().coerceIn(0, last)
    }

    companion object {
        /** Silence must not be stretched to full contrast. */
        const val FLOOR_TOP_DB = -50f
    }
}
