package ru.dvedev.me.cupola.dsp.pitch

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 * Spectral harmonic-comb pitch logic (SPEC §5.2 harmonic tracker as a sanity check and as
 * a fallback detector).
 *
 * [refine] checks a time-domain estimate: candidates `f0 · {1/3, 1/2, 2/3, 1, 3/2, 2, 3, 4, 5, 6}`
 * are scored and the lowest candidate within [acceptRatio] of the best wins. [search] scans a
 * log grid over [minHz, maxHz] when the time-domain detector has no confident estimate
 * (voice over an orchestra, heavy reverb) and returns the best-scoring fundamental with a
 * confidence derived from the score.
 *
 * Scoring a candidate `c`: its fundamental must be a real local peak (not the skirt of a
 * stronger line); harmonic peaks above the noise floor add `min(above, 40)/k`; energy found
 * halfway between harmonics that is comparable to them is subtracted (a simplified two-way
 * mismatch, so an octave-too-high candidate pays for the odd harmonics it leaves
 * unexplained). In [refine], a candidate below the estimate must also carry harmonics of
 * its own, not just multiples of the estimate.
 */
class HarmonicCombRefiner(
    val minHz: Double = 60.0,
    val maxHz: Double = 1200.0,
    val harmonics: Int = 10,
    val toleranceRatio: Double = 0.03,
    /** A harmonic counts when this far above the per-bin floor (the 10th-percentile floor sits ~12 dB under noise peaks). */
    val aboveFloorDb: Double = 12.0,
    /** The candidate's fundamental must rise this far above its own skirts. */
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
    /** The fundamental may not be weaker than the candidate's strongest harmonic by more than this. */
    val fundamentalBelowStrongestDb: Double = 30.0,
    /** At least this many of the first three harmonics must be audible (a shared sub-harmonic of two voices has none). */
    val minLowHarmonics: Int = 2,
    /** Grid step of [search] / [candidates], cents. */
    val searchStepCents: Double = 25.0,
    /** [search] score that maps to confidence 1 (a clean voice scores ~100). */
    val searchFullScore: Double = 60.0,
) {
    private val ratios = doubleArrayOf(1.0 / 3, 0.5, 2.0 / 3, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0)
    private val scores = DoubleArray(ratios.size)
    private val audible = IntArray(ratios.size)
    private val level = DoubleArray(harmonics + 1)
    private val floor = DoubleArray(harmonics + 1)
    private val peakHz = DoubleArray(harmonics + 1)
    /** Harmonic k is a spectral peak (local maximum) sitting within tolerance of k·c. */
    private val present = BooleanArray(harmonics + 1)
    /** Harmonic k contributed to the last score (present, above the floor, prominent). */
    private val counted = BooleanArray(harmonics + 1)
    private val gridHz: DoubleArray = run {
        val n = (1200.0 * ln(maxHz / minHz) / ln(2.0) / searchStepCents).toInt() + 1
        DoubleArray(n) { minHz * 2.0.pow(it * searchStepCents / 1200.0) }
    }

    data class Result(val f0Hz: Double, val confidence: Double, val changed: Boolean, val audibleHarmonics: Int)

    private fun peakLevel(spectrum: PowerSpectrum, hz: Double, halfHz: Double): Double =
        spectrum.db[spectrum.peakBin(hz - halfHz, hz + halfHz)]

    /**
     * Comb score of candidate [c]; NaN when the candidate is rejected. Fills [level]/[floor]/
     * [peakHz] for `k = 1..n` and returns the audible count through [countOut].
     */
    private fun score(c: Double, spectrum: PowerSpectrum, noise: NoiseFloor, ratio: Double, strongest: Double, countOut: IntArray, minLow: Int = minLowHarmonics): Double {
        val nyquist = spectrum.nyquistHz
        // (the fundamental must be a real local-maximum peak — checked in the harmonic loop
        // below; a prominence test against bins a few away vetoed voices next to louder
        // orchestral lines)
        lastReject = 0

        var n = 0
        while (n < harmonics && (n + 1) * c < nyquist) {
            val target = (n + 1) * c
            // a harmonic is a LOCAL MAXIMUM bin whose interpolated frequency lies within
            // tolerance of k·c (never narrower than the bin resolution) — a window that just
            // takes the strongest bin nearby would credit the skirt of a neighbouring line
            val tol = maxOf(target * toleranceRatio, 0.6 * spectrum.binHz)
            val tol2 = minOf(tol, 0.4 * c)
            var lo = spectrum.binFloor(target - tol2).coerceAtLeast(1)
            var hi = spectrum.binCeil(target + tol2).coerceAtMost(spectrum.bins - 2)
            var bestBin = -1
            var bestDb = -1e9
            var bestHz = target
            for (b in lo..hi) {
                val v = spectrum.db[b]
                if (v < spectrum.db[b - 1] || v < spectrum.db[b + 1] || v <= bestDb) continue
                val hz = spectrum.refinePeakHz(b)
                if (abs(hz - target) > tol2) continue
                bestBin = b; bestDb = v; bestHz = hz
            }
            if (bestBin < 0) {
                bestBin = spectrum.peakBin(target - tol2, target + tol2)
                present[n] = false
            } else {
                present[n] = true
            }
            level[n] = spectrum.db[bestBin]
            floor[n] = noise.floorAt(bestBin)
            peakHz[n] = bestHz
            n++
        }
        if (n == 0 || !present[0] || level[0] - floor[0] < aboveFloorDb) { lastReject = 2; return Double.NaN }

        var s = 0.0
        var count = 0
        var ownMax = -200.0
        var lowCount = 0
        var maxLevel = -200.0
        for (k in 1..n) if (level[k - 1] > maxLevel) maxLevel = level[k - 1]
        if (level[0] < maxLevel - fundamentalBelowStrongestDb) { lastReject = 3; return Double.NaN }
        for (k in 1..n) {
            counted[k - 1] = false
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
                if (vAbove > 0 && valley >= neighbour - valleyRelativeDb) s -= minOf(vAbove, maxContributionDb) / k
            }
            if (ratio < 1.0) {
                val m = k * ratio
                if (abs(m - round(m)) > 1e-6 && lv > ownMax) ownMax = lv
            }
            if (present[k - 1] && above > 0 && lv - valley >= prominenceDb) {
                counted[k - 1] = true
                s += minOf(above, maxContributionDb) / k
                count++
                if (k <= 3) lowCount++
            }
        }
        if (lowCount < minOf(minLow, n)) { lastReject = 4; return Double.NaN }
        if (ratio < 1.0 && ownMax < strongest - subharmonicEvidenceDb) { lastReject = 5; return Double.NaN }
        countOut[0] = count
        return s
    }

    private val countScratch = IntArray(1)
    /** Why the last [score] call rejected its candidate (trace): 0 none, 1 not a peak, 2 fundamental below floor, 3 fundamental weak vs strongest, 4 too few low harmonics, 5 subharmonic evidence. */
    var lastReject = 0
        private set

    /** One scored candidate of [candidates]. */
    class Candidate {
        var hz = 0.0
        var score = 0.0
        var count = 0
    }

    /**
     * Comb score of an arbitrary frequency (NaN when rejected) with the fundamental refined
     * from its harmonic peaks — for the tracker's own candidates (YIN and its octaves).
     */
    fun scoreAt(hz: Double, spectrum: PowerSpectrum, noise: NoiseFloor, out: Candidate, minLow: Int = minLowHarmonics): Boolean {
        if (hz < minHz || hz > maxHz) return false
        val s = score(hz, spectrum, noise, 1.0, -200.0, countScratch, minLow)
        if (s.isNaN() || s <= 0.0) return false
        out.hz = refinedHz(hz, spectrum)
        out.score = s
        out.count = countScratch[0]
        return true
    }

    /** Uses [level]/[floor]/[peakHz] left by the last [score] call for this frequency. */
    private fun refinedHz(c: Double, spectrum: PowerSpectrum): Double {
        var sumF = 0.0
        var sumW = 0.0
        var k = 1
        while (k <= harmonics && k * c < spectrum.nyquistHz) {
            val above = level[k - 1] - floor[k - 1] - aboveFloorDb
            if (counted[k - 1]) {
                // higher harmonics resolve finer, but only clearly audible ones may pull
                val w = k * minOf(above, 20.0) / 20.0
                sumF += peakHz[k - 1] / k * w
                sumW += w
            }
            k++
        }
        return if (sumW > 0) sumF / sumW else c
    }

    private val gridScores = DoubleArray(gridHz.size)
    private val gridCounts = IntArray(gridHz.size)

    /**
     * Scans the grid and returns the strongest local maxima (up to [out].size), best first.
     * Returns how many were written.
     */
    fun candidates(spectrum: PowerSpectrum, noise: NoiseFloor, out: Array<Candidate>): Int {
        for (i in gridHz.indices) {
            val s = score(gridHz[i], spectrum, noise, 1.0, -200.0, countScratch)
            gridScores[i] = if (s.isNaN()) -1.0 else s
            gridCounts[i] = countScratch[0]
        }
        var n = 0
        for (i in gridHz.indices) {
            val s = gridScores[i]
            if (s <= 0.0) continue
            val left = if (i > 0) gridScores[i - 1] else -1.0
            val right = if (i + 1 < gridHz.size) gridScores[i + 1] else -1.0
            if (s < left || s < right) continue
            // refine from the harmonic peaks; grid points that resolve to the same series merge
            score(gridHz[i], spectrum, noise, 1.0, -200.0, countScratch)
            val hz = refinedHz(gridHz[i], spectrum)
            var dup = -1
            for (j in 0 until n) if (abs(out[j].hz - hz) / hz < toleranceRatio) { dup = j; break }
            if (dup >= 0) {
                if (s > out[dup].score) { out[dup].score = s; out[dup].count = gridCounts[i]; out[dup].hz = hz }
                continue
            }
            var pos = n
            while (pos > 0 && out[pos - 1].score < s) pos--
            if (pos >= out.size) continue
            val last = if (n < out.size) n else out.size - 1
            for (j in last downTo pos + 1) { out[j].hz = out[j - 1].hz; out[j].score = out[j - 1].score; out[j].count = out[j - 1].count }
            out[pos].hz = hz
            out[pos].score = s
            out[pos].count = gridCounts[i]
            if (n < out.size) n++
        }
        // keep the list sorted after in-place merges raised a score
        for (a in 1 until n) {
            var b = a
            while (b > 0 && out[b - 1].score < out[b].score) {
                val th = out[b].hz; val ts = out[b].score; val tc = out[b].count
                out[b].hz = out[b - 1].hz; out[b].score = out[b - 1].score; out[b].count = out[b - 1].count
                out[b - 1].hz = th; out[b - 1].score = ts; out[b - 1].count = tc
                b--
            }
        }
        return n
    }

    fun refine(estimate: PitchEstimate, spectrum: PowerSpectrum, noise: NoiseFloor): Result {
        val f0 = estimate.f0Hz
        if (f0 <= 0.0) return Result(f0, estimate.confidence, false, 0)
        val nyquist = spectrum.nyquistHz
        var strongest = -200.0
        var k = 1
        while (k <= harmonics && k * f0 < nyquist) {
            strongest = maxOf(strongest, peakLevel(spectrum, k * f0, minOf(k * f0 * toleranceRatio, 0.4 * f0)))
            k++
        }
        var best = -1.0
        for (i in ratios.indices) {
            val c = f0 * ratios[i]
            scores[i] = -1.0
            audible[i] = 0
            if (c < minHz || c > maxHz) continue
            val s = score(c, spectrum, noise, ratios[i], strongest, countScratch)
            if (s.isNaN()) continue
            scores[i] = s
            audible[i] = countScratch[0]
            if (s > best) best = s
        }
        if (best <= 0.0) return Result(f0, estimate.confidence, false, 0)
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

    /**
     * Grid search for the best harmonic series in the spectrum. Returns null when nothing
     * scores; otherwise f0 refined from the harmonic peak positions and a confidence
     * `min(0.95, score / searchFullScore)`.
     */
    fun search(spectrum: PowerSpectrum, noise: NoiseFloor): Result? {
        var bestScore = 0.0
        var bestHz = 0.0
        var bestCount = 0
        for (c in gridHz) {
            val s = score(c, spectrum, noise, 1.0, -200.0, countScratch)
            if (!s.isNaN() && s > bestScore) { bestScore = s; bestHz = c; bestCount = countScratch[0] }
        }
        if (bestHz <= 0.0) return null
        // sub-harmonic guard: half the best must not explain the same series better
        if (bestHz / 2 >= minHz) {
            val half = score(bestHz / 2, spectrum, noise, 1.0, -200.0, countScratch)
            if (!half.isNaN() && half >= acceptRatio * bestScore) { bestHz /= 2; bestScore = half; bestCount = countScratch[0] }
        }
        // refine f0 from the audible harmonic peaks (higher harmonics resolve finer)
        score(bestHz, spectrum, noise, 1.0, -200.0, countScratch)
        var sumF = 0.0
        var sumW = 0.0
        var k = 1
        while (k <= harmonics && k * bestHz < spectrum.nyquistHz) {
            if (counted[k - 1]) {
                val wgt = k.toDouble()
                sumF += peakHz[k - 1] / k * wgt
                sumW += wgt
            }
            k++
        }
        val f0 = if (sumW > 0) sumF / sumW else bestHz
        return Result(f0, minOf(0.95, bestScore / searchFullScore), true, bestCount)
    }
}
