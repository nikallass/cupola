package ru.dvedev.me.cupola.testdata

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A spectral bump: [gainDb] at [centerHz], Gaussian fall-off with [bandwidthHz] as the ±1σ width. */
data class Formant(val centerHz: Double, val bandwidthHz: Double, val gainDb: Double) {
    fun gainDbAt(hz: Double): Double {
        val z = (hz - centerHz) / (bandwidthHz / 2.0)
        return gainDb * exp(-0.5 * z * z)
    }
}

/**
 * Additive harmonic voice: harmonics `k·f0(t)` up to Nyquist with a spectral tilt of
 * [tiltDbPerOctave] and optional [formants] (vowel formants, a boosted ring band, …).
 * Peak amplitude is normalised to [amplitude] (full scale = 1).
 */
data class VoiceSpec(
    val contour: PitchContour,
    val tiltDbPerOctave: Double = -12.0,
    val formants: List<Formant> = emptyList(),
    val maxHarmonics: Int = 60,
    val amplitude: Double = 0.3,
)

/** Synthetic signal generators for the SPEC §10 test suite. All output is mono `DoubleArray`, full scale ±1. */
object Signals {
    const val SAMPLE_RATE = 48_000

    fun samples(seconds: Double, sampleRate: Int = SAMPLE_RATE): Int = (seconds * sampleRate).toInt()

    fun sine(hz: Double, seconds: Double, sampleRate: Int = SAMPLE_RATE, amplitude: Double = 0.5, phase: Double = 0.0): DoubleArray {
        val n = samples(seconds, sampleRate)
        val w = 2 * PI * hz / sampleRate
        return DoubleArray(n) { i -> amplitude * sin(w * i + phase) }
    }

    /** Band-limited sawtooth (harmonics `1/k`, −6 dB/octave) — the classic "rich" test source. */
    fun sawtooth(hz: Double, seconds: Double, sampleRate: Int = SAMPLE_RATE, amplitude: Double = 0.5): DoubleArray =
        harmonicVoice(VoiceSpec(PitchContour.Constant(hz), tiltDbPerOctave = -6.0, maxHarmonics = 200, amplitude = amplitude), seconds, sampleRate)

    fun harmonicVoice(spec: VoiceSpec, seconds: Double, sampleRate: Int = SAMPLE_RATE): DoubleArray {
        val n = samples(seconds, sampleRate)
        val out = DoubleArray(n)
        val phases = DoubleArray(spec.maxHarmonics)
        val nyquist = sampleRate / 2.0 * 0.95
        val twoPiOverFs = 2 * PI / sampleRate
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val f0 = spec.contour.hzAt(t)
            var s = 0.0
            var k = 1
            while (k <= spec.maxHarmonics && k * f0 < nyquist) {
                val hz = k * f0
                var gainDb = spec.tiltDbPerOctave * ln(k.toDouble()) / ln(2.0)
                for (f in spec.formants) gainDb += f.gainDbAt(hz)
                phases[k - 1] += twoPiOverFs * hz
                s += dbToLinear(gainDb) * sin(phases[k - 1])
                k++
            }
            out[i] = s
        }
        normalisePeak(out, spec.amplitude)
        return out
    }

    /** Gaussian white noise with RMS [rms] (σ), clipped at ±1. */
    fun whiteNoise(seconds: Double, sampleRate: Int = SAMPLE_RATE, rms: Double = 0.1, seed: Int = 1): DoubleArray {
        val n = samples(seconds, sampleRate)
        val rnd = Random(seed)
        return DoubleArray(n) { (gaussian(rnd) * rms).coerceIn(-1.0, 1.0) }
    }

    /** Pink (1/f) noise via Paul Kellet's filter, scaled to RMS [rms]. */
    fun pinkNoise(seconds: Double, sampleRate: Int = SAMPLE_RATE, rms: Double = 0.1, seed: Int = 1): DoubleArray {
        val n = samples(seconds, sampleRate)
        val rnd = Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val white = gaussian(rnd)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            out[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
        }
        normaliseRms(out, rms)
        return out
    }

    fun silence(seconds: Double, sampleRate: Int = SAMPLE_RATE): DoubleArray = DoubleArray(samples(seconds, sampleRate))

    /** Sample-wise sum; shorter inputs are zero-padded. */
    fun mix(vararg signals: DoubleArray): DoubleArray {
        val n = signals.maxOf { it.size }
        val out = DoubleArray(n)
        for (s in signals) for (i in s.indices) out[i] += s[i]
        return out
    }

    fun concat(vararg signals: DoubleArray): DoubleArray {
        val out = DoubleArray(signals.sumOf { it.size })
        var pos = 0
        for (s in signals) {
            s.copyInto(out, pos)
            pos += s.size
        }
        return out
    }

    fun gain(signal: DoubleArray, gainDb: Double): DoubleArray {
        val g = dbToLinear(gainDb)
        return DoubleArray(signal.size) { signal[it] * g }
    }

    /** Linear attack / release envelope, in seconds. */
    fun envelope(signal: DoubleArray, attackSeconds: Double, releaseSeconds: Double, sampleRate: Int = SAMPLE_RATE): DoubleArray {
        val a = max(1, (attackSeconds * sampleRate).toInt())
        val r = max(1, (releaseSeconds * sampleRate).toInt())
        val n = signal.size
        return DoubleArray(n) { i ->
            val env = when {
                i < a -> i.toDouble() / a
                i >= n - r -> (n - 1 - i).toDouble() / r
                else -> 1.0
            }
            signal[i] * env
        }
    }

    fun rms(signal: DoubleArray, from: Int = 0, to: Int = signal.size): Double {
        var acc = 0.0
        for (i in from until to) acc += signal[i] * signal[i]
        return sqrt(acc / max(1, to - from))
    }

    fun rmsDb(signal: DoubleArray): Double = linearToDb(rms(signal))

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    fun linearToDb(x: Double): Double = 20.0 * log10(max(x, 1e-12))

    private fun normalisePeak(x: DoubleArray, peak: Double) {
        var m = 0.0
        for (v in x) m = max(m, abs(v))
        if (m > 0) {
            val g = peak / m
            for (i in x.indices) x[i] *= g
        }
    }

    private fun normaliseRms(x: DoubleArray, target: Double) {
        val r = rms(x)
        if (r > 0) {
            val g = target / r
            for (i in x.indices) x[i] *= g
        }
    }

    private fun gaussian(rnd: Random): Double {
        // Box–Muller
        val u1 = 1.0 - rnd.nextDouble()
        val u2 = rnd.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * PI * u2)
    }
}
