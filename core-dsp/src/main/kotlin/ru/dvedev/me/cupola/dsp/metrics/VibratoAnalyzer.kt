package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.fft.RealFft
import ru.dvedev.me.cupola.dsp.frame.Windows
import ru.dvedev.me.cupola.dsp.util.RingHistory
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Informational classification of the pitch modulation (SPEC §5.5, §15.5). */
enum class VibratoKind {
    /** Not enough voiced contour yet. */
    NONE,
    /** Extent < ±15 ¢. */
    STRAIGHT,
    /** 4–7.5 Hz and ≤ ±120 ¢ — healthy vibrato, never penalised. */
    VIBRATO,
    /** Slower than 4 Hz or wider than ±120 ¢ — «качание». */
    WOBBLE,
    /** Faster than 7.5 Hz — «тремоло». */
    TREMOLO,
}

data class Vibrato(val rateHz: Double, val extentCents: Double, val kind: VibratoKind) {
    companion object {
        val NONE = Vibrato(Double.NaN, Double.NaN, VibratoKind.NONE)
    }
}

/**
 * Estimates vibrato rate and extent from the residual contour (`cents − median line`)
 * over the last [windowSeconds]: Hann window, zero-padded FFT, peak in 1.5–15 Hz with
 * parabolic refinement; extent is the interpolated peak amplitude (half-swing in cents).
 * Recomputed every [everyFrames] frames; [current] holds the latest result.
 */
/**
 * Borders between a straight tone, vibrato, wobble and tremolo: a modulation narrower than
 * [straightMaxCents] is straight; faster than [vibratoMaxHz] is a tremolo; slower than
 * [vibratoMinHz] or wider than [vibratoMaxCents] is a wobble; the rest is vibrato.
 */
data class VibratoThresholds(
    val straightMaxCents: Double = VibratoAnalyzer.STRAIGHT_MAX_CENTS,
    val vibratoMinHz: Double = VibratoAnalyzer.VIBRATO_MIN_HZ,
    val vibratoMaxHz: Double = VibratoAnalyzer.VIBRATO_MAX_HZ,
    val vibratoMaxCents: Double = VibratoAnalyzer.VIBRATO_MAX_CENTS,
)

class VibratoAnalyzer(
    val hopSeconds: Double,
    val windowSeconds: Double = 2.0,
    val minVoicedSeconds: Double = 1.0,
    val everyFrames: Int = 5,
    val minRateHz: Double = 1.5,
    val maxRateHz: Double = 15.0,
) {
    private val windowFrames = (windowSeconds / hopSeconds).roundToInt()
    private val minVoiced = (minVoicedSeconds / hopSeconds).roundToInt()
    private val history = RingHistory(windowFrames)
    private val fftSize = Integer.highestOneBit(windowFrames * 4 - 1) shl 1
    private val fft = RealFft(fftSize)
    private val buf = DoubleArray(fftSize)
    private val re = DoubleArray(fft.bins)
    private val im = DoubleArray(fft.bins)
    private val magDb = DoubleArray(fft.bins)
    private val window = Windows.hann(windowFrames)
    private val windowSum = Windows.sum(window)
    private val binHz = 1.0 / (hopSeconds * fftSize)
    private var counter = 0

    var current: Vibrato = Vibrato.NONE
        private set

    /** Classification borders (SPEC §15.5; adjustable in advanced settings since 2026‑09‑16). */
    @Volatile var thresholds: VibratoThresholds = VibratoThresholds()

    /** Push one frame's residual (NaN when unvoiced) and return the current estimate. */
    fun push(residualCents: Double): Vibrato {
        history.push(residualCents)
        if (++counter >= everyFrames) {
            counter = 0
            current = analyse()
        }
        return current
    }

    fun reset() {
        history.clear()
        counter = 0
        current = Vibrato.NONE
    }

    private fun analyse(): Vibrato {
        if (history.size < windowFrames || history.validCount() < minVoiced) return Vibrato.NONE
        // fill: valid residuals minus their mean, unvoiced gaps as zeros
        val mean = history.mean()
        var n = 0
        for (i in 0 until windowFrames) {
            val v = history.at(i)
            buf[i] = if (v.isNaN()) 0.0 else (v - mean) * window[i]
            if (!v.isNaN()) n++
        }
        for (i in windowFrames until fftSize) buf[i] = 0.0
        fft.forward(buf, re, im)
        val lo = (minRateHz / binHz).toInt().coerceAtLeast(1)
        val hi = (maxRateHz / binHz).toInt().coerceAtMost(fft.bins - 2)
        var best = lo
        for (k in lo..hi) {
            magDb[k] = 10.0 * log10(re[k] * re[k] + im[k] * im[k] + 1e-20)
            if (magDb[k] > magDb[best]) best = k
        }
        magDb[lo - 1] = 10.0 * log10(re[lo - 1] * re[lo - 1] + im[lo - 1] * im[lo - 1] + 1e-20)
        magDb[hi + 1] = 10.0 * log10(re[hi + 1] * re[hi + 1] + im[hi + 1] * im[hi + 1] + 1e-20)
        val a = magDb[best - 1]
        val b = magDb[best]
        val c = magDb[best + 1]
        val denom = a - 2 * b + c
        val delta = if (denom == 0.0) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        val peakDb = b - 0.25 * (a - c) * delta
        val rate = (best + delta) * binHz
        // amplitude of a sinusoid: 2·|X| / Σw, scaled for the voiced fraction of the window
        val voicedFraction = n.toDouble() / windowFrames
        val amplitude = 2.0 * 10.0.pow(peakDb / 20.0) / (windowSum * voicedFraction)
        // sanity: a pure-noise contour has no dominant peak; require the peak to carry a
        // reasonable share of the residual energy
        val rms = residualRms(mean)
        val extent = amplitude.coerceAtMost(rms * sqrt(2.0) * 1.5)
        return Vibrato(rate, extent, classify(rate, extent, thresholds))
    }

    private fun residualRms(mean: Double): Double {
        var acc = 0.0
        var n = 0
        for (i in 0 until windowFrames) {
            val v = history.at(i)
            if (!v.isNaN()) { acc += (v - mean) * (v - mean); n++ }
        }
        return if (n == 0) 0.0 else sqrt(acc / n)
    }

    companion object {
        const val STRAIGHT_MAX_CENTS = 15.0
        const val VIBRATO_MIN_HZ = 4.0
        const val VIBRATO_MAX_HZ = 7.5
        const val VIBRATO_MAX_CENTS = 120.0

        fun classify(rateHz: Double, extentCents: Double, t: VibratoThresholds = VibratoThresholds()): VibratoKind = when {
            extentCents < t.straightMaxCents -> VibratoKind.STRAIGHT
            rateHz > t.vibratoMaxHz -> VibratoKind.TREMOLO
            rateHz < t.vibratoMinHz || extentCents > t.vibratoMaxCents -> VibratoKind.WOBBLE
            else -> VibratoKind.VIBRATO
        }
    }
}
