package ru.dvedev.me.cupola.analysis

import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.metrics.Harmonic
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor

/**
 * Latest spectrum for the spectrum zone (T-054): dB per bin, harmonics, noise profile.
 * Double-buffered so the UI thread never reads a half-written frame.
 */
class SpectrumSnapshot(private val smoothing: Float = 0.5f, private val noise: () -> NoiseFloor?) : FrameListener {
    private var ema: FloatArray = FloatArray(0)

    class Frame(bins: Int) {
        val db = FloatArray(bins)
        val floorDb = FloatArray(bins)
        var binHz = 0.0
        var harmonics: List<Harmonic> = emptyList()
        var f0Hz = 0.0
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
        val a = 1f - smoothing
        for (k in db.indices) {
            ema[k] += (db[k].toFloat() - ema[k]) * a
            f.db[k] = ema[k]
        }
        val n = noise()
        if (n != null) for (k in db.indices) f.floorDb[k] = n.profileDb[k].toFloat()
        f.binHz = spectrum.binHz
        f.harmonics = metrics.harmonics
        f.f0Hz = metrics.f0Hz
        f.voiced = metrics.voiced
        f.timeSec = metrics.timeSec
        current = next
    }
}
