package ru.dvedev.me.cupola.dsp.fft

import ru.dvedev.me.cupola.dsp.frame.Windows
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Power spectrum of a windowed frame, in linear power and dBFS.
 *
 * Normalisation: a full-scale sine (amplitude 1) whose frequency sits exactly on a bin
 * gives `db ≈ 0` at that bin, so levels read as dBFS of sine amplitude. Linear [power]
 * is what band energies `E[a–b]` (SPEC §5) sum over.
 */
class PowerSpectrum(val fftSize: Int, val sampleRate: Int, window: DoubleArray = Windows.hann(fftSize)) {
    private val fft = RealFft(fftSize)
    val bins: Int = fft.bins
    val binHz: Double = sampleRate.toDouble() / fftSize
    val nyquistHz: Double = sampleRate / 2.0

    private val re = DoubleArray(bins)
    private val im = DoubleArray(bins)
    private val scaleInner: Double
    private val scaleEdge: Double

    /** Linear power per bin. */
    val power: DoubleArray = DoubleArray(bins)

    /** `10·log10(power)` per bin, floored at [FLOOR_DB]. */
    val db: DoubleArray = DoubleArray(bins) { FLOOR_DB }

    init {
        val s = Windows.sum(window)
        scaleInner = (2.0 / s) * (2.0 / s)
        scaleEdge = (1.0 / s) * (1.0 / s)
    }

    fun compute(windowed: DoubleArray) {
        fft.forward(windowed, re, im)
        for (k in 0 until bins) {
            val p = (re[k] * re[k] + im[k] * im[k]) * (if (k == 0 || k == bins - 1) scaleEdge else scaleInner)
            power[k] = p
            db[k] = if (p > FLOOR_POWER) 10.0 * log10(p) else FLOOR_DB
        }
    }

    fun hzOf(bin: Int): Double = bin * binHz
    fun binOf(hz: Double): Int = (hz / binHz).roundToInt().coerceIn(0, bins - 1)
    fun binFloor(hz: Double): Int = (hz / binHz).toInt().coerceIn(0, bins - 1)
    fun binCeil(hz: Double): Int = kotlin.math.ceil(hz / binHz).toInt().coerceIn(0, bins - 1)

    /** `E[lo–hi]`: sum of linear power over bins whose centre lies in `[loHz, hiHz]`. */
    fun energy(loHz: Double, hiHz: Double): Double {
        var e = 0.0
        for (k in binCeil(loHz)..binFloor(hiHz)) e += power[k]
        return e
    }

    /** Highest bin level (dB) in `[loHz, hiHz]`. */
    fun peakDb(loHz: Double, hiHz: Double): Double {
        var best = FLOOR_DB
        for (k in binCeil(loHz)..binFloor(hiHz)) if (db[k] > best) best = db[k]
        return best
    }

    /** Index of the highest bin in `[loHz, hiHz]`. */
    fun peakBin(loHz: Double, hiHz: Double): Int {
        var best = binCeil(loHz)
        for (k in binCeil(loHz)..binFloor(hiHz)) if (power[k] > power[best]) best = k
        return best
    }

    /**
     * Parabolic refinement of a peak at [bin]: returns the interpolated frequency in Hz.
     * Uses log-magnitude interpolation, which is accurate for Hann-windowed sines.
     */
    fun refinePeakHz(bin: Int): Double {
        if (bin <= 0 || bin >= bins - 1) return hzOf(bin)
        val a = db[bin - 1]
        val b = db[bin]
        val c = db[bin + 1]
        val denom = a - 2 * b + c
        val delta = if (denom == 0.0) 0.0 else 0.5 * (a - c) / denom
        return (bin + delta.coerceIn(-0.5, 0.5)) * binHz
    }

    companion object {
        const val FLOOR_DB = -140.0
        const val FLOOR_POWER = 1e-14
    }
}
