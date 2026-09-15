package ru.dvedev.me.cupola.dsp.pitch

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor
import kotlin.math.abs
import kotlin.math.round

/**
 * Checks a time-domain pitch estimate against the spectrum (SPEC §5.2 harmonic tracker as
 * a sanity check). On real recordings — a voice over an orchestra, room reverb — YIN often
 * locks onto the bass period or an octave below/above: the reported f0 then has no audible
 * harmonics while a multiple of it carries the whole sung series.
 *
 * Candidates are `f0 · {1/3, 1/2, 2/3, 1, 3/2, 2, 3, 4, 5, 6}` inside [minHz, maxHz]. A candidate
 * needs a fundamental that is a real local peak (not the skirt of a stronger line). Its score
 * sums the harmonic peaks above the noise floor with `1/k` weights and subtracts energy found
 * halfway between harmonics when it is comparable to them (a simplified two-way mismatch: an
 * octave-too-high candidate leaves the odd harmonics unexplained and pays for them). A
 * candidate below the estimate must also have harmonics of its own, not just multiples of
 * the estimate. The lowest candidate whose score reaches [acceptRatio] of the best wins.
 */
class HarmonicCombRefiner(
    val minHz: Double = 60.0,
    val maxHz: Double = 1200.0,
    val harmonics: Int = 10,
    val toleranceRatio: Double = 0.03,
    /** A harmonic counts when this far above the per-bin floor (the 10th-percentile floor sits ~12 dB under noise peaks). */
    val aboveFloorDb: Double = 12.0,
    /** The candidate's fundamental must rise this far above its own skirts at ±10–20 %. */
    val peakProminenceDb: Double = 6.0,
    /** A harmonic must stand this far above the valley after it. */
    val prominenceDb: Double = 8.0,
    /** A valley is "unexplained energy" only when within this of the neighbouring harmonics. */
    val valleyRelativeDb: Double = 20.0,
    val maxContributionDb: Double = 40.0,
    val acceptRatio: Double = 0.9,
    /** A candidate below the estimate must have harmonics of its own within this of the estimate's strongest. */
    val subharmonicEvidenceDb: Double = 25.0,
    /** Confidence granted when the winner has at least [strongHarmonics] audible harmonics and YIN saw some periodicity. */
    val combConfidence: Double = 0.8,
    val strongHarmonics: Int = 4,
    val minEstimateConfidence: Double = 0.5,
) {
    private val ratios = doubleArrayOf(1.0 / 3, 0.5, 2.0 / 3, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0)
    private val scores = DoubleArray(ratios.size)
    private val audible = IntArray(ratios.size)
    private val level = DoubleArray(harmonics + 1)
    private val floor = DoubleArray(harmonics + 1)

    data class Result(val f0Hz: Double, val confidence: Double, val changed: Boolean, val audibleHarmonics: Int)

    private fun peakLevel(spectrum: PowerSpectrum, hz: Double, halfHz: Double): Double =
        spectrum.db[spectrum.peakBin(hz - halfHz, hz + halfHz)]

    fun refine(estimate: PitchEstimate, spectrum: PowerSpectrum, noise: NoiseFloor): Result {
        val f0 = estimate.f0Hz
        if (f0 <= 0.0) return Result(f0, estimate.confidence, false, 0)
        val nyquist = spectrum.nyquistHz

        // strongest harmonic of the estimate itself: the reference for sub-harmonic evidence
        var strongest = -200.0
        run {
            var k = 1
            while (k <= harmonics && k * f0 < nyquist) {
                strongest = maxOf(strongest, peakLevel(spectrum, k * f0, minOf(k * f0 * toleranceRatio, 0.4 * f0)))
                k++
            }
        }

        var best = -1.0
        for (i in ratios.indices) {
            val r = ratios[i]
            val c = f0 * r
            scores[i] = -1.0
            audible[i] = 0
            if (c < minHz || c > maxHz) continue

            // fundamental must be a local peak, not the skirt of a stronger line nearby;
            // the skirt windows stay outside the window's main lobe (≥ 3.5 bins away)
            val fund = peakLevel(spectrum, c, minOf(c * toleranceRatio, 0.4 * c))
            val d = maxOf(0.15 * c, 3.5 * spectrum.binHz)
            val w = maxOf(0.05 * c, spectrum.binHz)
            val skirtLo = peakLevel(spectrum, c - d, w)
            val skirtHi = peakLevel(spectrum, c + d, w)
            if (fund < maxOf(skirtLo, skirtHi) + peakProminenceDb) continue

            // harmonic levels
            var n = 0
            while (n < harmonics && (n + 1) * c < nyquist) {
                val target = (n + 1) * c
                val half = minOf(target * toleranceRatio, 0.4 * c)
                val bin = spectrum.peakBin(target - half, target + half)
                level[n] = spectrum.db[bin]
                floor[n] = noise.floorAt(bin)
                n++
            }
            if (n == 0) continue
            if (level[0] - floor[0] < aboveFloorDb) continue

            var score = 0.0
            var count = 0
            var ownMax = -200.0
            for (k in 1..n) {
                val lv = level[k - 1]
                val above = lv - floor[k - 1] - aboveFloorDb
                val valleyHz = (k + 0.5) * c
                var valley = -200.0
                if (valleyHz < nyquist) {
                    val vHalf = minOf(valleyHz * toleranceRatio, 0.25 * c)
                    val vBin = spectrum.peakBin(valleyHz - vHalf, valleyHz + vHalf)
                    valley = spectrum.db[vBin]
                    val neighbour = maxOf(lv, if (k < n) level[k] else -200.0)
                    val vAbove = valley - noise.floorAt(vBin) - aboveFloorDb
                    if (vAbove > 0 && valley >= neighbour - valleyRelativeDb) score -= minOf(vAbove, maxContributionDb) / k
                }
                if (r < 1.0) {
                    val m = k * r
                    if (abs(m - round(m)) > 1e-6 && lv > ownMax) ownMax = lv
                }
                if (above > 0 && lv - valley >= prominenceDb) {
                    score += minOf(above, maxContributionDb) / k
                    count++
                }
            }
            if (r < 1.0 && ownMax < strongest - subharmonicEvidenceDb) continue
            scores[i] = score
            audible[i] = count
            if (score > best) best = score
        }
        if (best <= 0.0) return Result(f0, estimate.confidence, false, 0)

        // lowest candidate that is nearly as good as the best one
        var chosen = -1
        for (i in ratios.indices) {
            if (scores[i] > 0.0 && scores[i] >= acceptRatio * best) { chosen = i; break }
        }
        if (chosen < 0) return Result(f0, estimate.confidence, false, 0)
        val refined = f0 * ratios[chosen]
        val count = audible[chosen]
        val conf = if (count >= strongHarmonics && estimate.confidence >= minEstimateConfidence) maxOf(estimate.confidence, combConfidence) else estimate.confidence
        return Result(refined, conf, ratios[chosen] != 1.0, count)
    }
}
