package ru.dvedev.me.cupola.analysis

import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.metrics.Harmonic
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor

/**
 * Latest spectrum for the spectrum zone (T-054): dB per bin, harmonics, noise profile.
 * Double-buffered so the UI thread never reads a half-written frame. Each bin is smoothed
 * in time (owner feedback 2026‑09‑16: «не как осциллоскоп»): rises with [riseSeconds],
 * falls with [fallSeconds], so the eye follows the trend instead of the frame-to-frame jitter.
 */
class SpectrumSnapshot(
    private val hopSeconds: Double = 0.01,
    private val riseSeconds: Double = 0.12,
    private val fallSeconds: Double = 0.27,
    private val noise: () -> NoiseFloor?,
) : FrameListener {
    private var ema: FloatArray = FloatArray(0)
    private var f0Log = Double.NaN
    private var lastVoiced = false
    private val riseAlpha = (1.0 - kotlin.math.exp(-hopSeconds / riseSeconds)).toFloat()
    private val fallAlpha = (1.0 - kotlin.math.exp(-hopSeconds / fallSeconds)).toFloat()

    class Frame(bins: Int) {
        val db = FloatArray(bins)
        val floorDb = FloatArray(bins)
        var binHz = 0.0
        var harmonics: List<Harmonic> = emptyList()
        var f0Hz = 0.0
        /** f0 smoothed with the spectrum's own rise time, so the harmonic lines move with the peaks. */
        var smoothF0Hz = 0.0
        var voiced = false
        var timeSec = 0.0
    }

    private var buffers: Array<Frame>? = null
    @Volatile private var current = 0
    @Volatile private var frozen: Frame? = null

    /** Newest complete frame (or the frozen one while paused), or null before the first one. */
    val latest: Frame? get() = frozen ?: buffers?.get(current)

    /** Keeps a copy of the current frame until [unfreeze] (pause, T-056). */
    fun freeze() {
        val src = buffers?.get(current) ?: return
        val f = Frame(src.db.size)
        src.db.copyInto(f.db)
        src.floorDb.copyInto(f.floorDb)
        f.binHz = src.binHz
        f.harmonics = src.harmonics
        f.f0Hz = src.f0Hz
        f.smoothF0Hz = src.smoothF0Hz
        f.voiced = src.voiced
        f.timeSec = src.timeSec
        frozen = f
    }

    fun unfreeze() {
        frozen = null
    }

    override fun onFrame(metrics: FrameMetrics, spectrum: PowerSpectrum) {
        var b = buffers
        if (b == null || b[0].db.size != spectrum.bins) {
            b = arrayOf(Frame(spectrum.bins), Frame(spectrum.bins))
            buffers = b
        }
        val next = 1 - current
        val f = b[next]
        val db = spectrum.db
        if (ema.size != db.size) ema = FloatArray(db.size) { db[it].toFloat() }
        for (k in db.indices) {
            val v = db[k].toFloat()
            ema[k] += (v - ema[k]) * (if (v > ema[k]) riseAlpha else fallAlpha)
            f.db[k] = ema[k]
        }
        val n = noise()
        if (n != null) for (k in db.indices) f.floorDb[k] = n.profileDb[k].toFloat()
        f.binHz = spectrum.binHz
        f.harmonics = metrics.harmonics
        f.f0Hz = metrics.f0Hz
        // harmonic lines follow the peaks (owner 2026‑09‑16): the same rise constant as the
        // bins, in the log domain; a new phrase snaps, silence keeps the last value
        if (metrics.voiced && metrics.f0Hz > 0) {
            val target = kotlin.math.ln(metrics.f0Hz)
            f0Log = if (f0Log.isNaN() || !lastVoiced) target else f0Log + (target - f0Log) * riseAlpha
        }
        lastVoiced = metrics.voiced && metrics.f0Hz > 0
        f.smoothF0Hz = if (f0Log.isNaN()) 0.0 else kotlin.math.exp(f0Log)
        f.voiced = metrics.voiced
        f.timeSec = metrics.timeSec
        current = next
    }
}
