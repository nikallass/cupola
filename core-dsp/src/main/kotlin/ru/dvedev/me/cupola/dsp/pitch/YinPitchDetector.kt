package ru.dvedev.me.cupola.dsp.pitch

import kotlin.math.ceil
import kotlin.math.floor

/**
 * YIN (de Cheveigné & Kawahara 2002): difference function → cumulative mean normalised
 * difference → absolute threshold → parabolic interpolation.
 *
 * Works on frames of [frameSize] samples; the correlation window is `frameSize − τmax`
 * so the whole lag range fits without wrap-around. Buffers are preallocated; no
 * allocation per call.
 */
class YinPitchDetector(
    val frameSize: Int = 2048,
    override val minHz: Double = 60.0,
    override val maxHz: Double = 1200.0,
    val threshold: Double = 0.12,
) : PitchDetector {
    private val d = DoubleArray(frameSize / 2 + 1)
    private val cmnd = DoubleArray(frameSize / 2 + 1)

    override fun estimate(frame: DoubleArray, sampleRate: Int): PitchEstimate {
        require(frame.size >= frameSize) { "frame shorter than frameSize" }
        val tauMin = maxOf(2, floor(sampleRate / maxHz).toInt())
        val tauMax = minOf(frameSize / 2, ceil(sampleRate / minHz).toInt())
        val w = frameSize - tauMax
        if (w <= tauMax) return PitchEstimate.NONE

        // energy gate: an all-zero frame has no pitch
        var energy = 0.0
        for (j in 0 until w) energy += frame[j] * frame[j]
        if (energy < 1e-10) return PitchEstimate.NONE

        // difference function
        d[0] = 0.0
        for (tau in 1..tauMax) {
            var acc = 0.0
            for (j in 0 until w) {
                val diff = frame[j] - frame[j + tau]
                acc += diff * diff
            }
            d[tau] = acc
        }

        // cumulative mean normalised difference
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += d[tau]
            cmnd[tau] = if (running > 0) d[tau] * tau / running else 1.0
        }

        // absolute threshold: first dip below threshold, then follow it to its local minimum
        var tau = -1
        var t = tauMin
        while (t < tauMax) {
            if (cmnd[t] < threshold) {
                while (t + 1 < tauMax && cmnd[t + 1] < cmnd[t]) t++
                tau = t
                break
            }
            t++
        }
        if (tau < 0) {
            // no dip below threshold: take the global minimum, confidence will be low
            var best = tauMin
            for (k in tauMin..tauMax) if (cmnd[k] < cmnd[best]) best = k
            tau = best
        }

        // parabolic interpolation around tau
        val refined = if (tau in 1 until tauMax) {
            val a = cmnd[tau - 1]
            val b = cmnd[tau]
            val c = cmnd[tau + 1]
            val denom = a - 2 * b + c
            if (denom != 0.0) tau + (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else tau.toDouble()
        } else {
            tau.toDouble()
        }
        val confidence = (1.0 - cmnd[tau]).coerceIn(0.0, 1.0)
        val f0 = sampleRate / refined
        if (f0 < minHz || f0 > maxHz) return PitchEstimate(0.0, confidence)
        return PitchEstimate(f0, confidence)
    }
}
