package ru.dvedev.me.cupola.dsp.pitch

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor
import kotlin.math.abs
import kotlin.math.ln

/**
 * Frame-to-frame pitch tracker (owner feedback 2026‑09‑15: a confidently found note must
 * survive a burst of octave-hopping estimates, and vibrato must read as one note).
 *
 * Every voiced frame gathers candidates — the strongest harmonic series in the spectrum
 * ([HarmonicCombRefiner.candidates]) plus the time-domain estimate and its octaves, the
 * series YIN heard getting a bonus scaled by its confidence — and then:
 *
 * - **on track**: the best candidate within [glideOctaves] of the tracked pitch is followed
 *   when its score reaches [nearScore] (vibrato, glides and small steps);
 * - **leap**: a *strong* series (score ≥ [strongScore], ≥ [strongCount] harmonics) farther
 *   away takes over only after it has persisted for [jumpSeconds] and, while a near
 *   candidate exists, beats it by [jumpRatio]. Until then a track confirmed for
 *   [confirmSeconds] is held at the tracked pitch with [holdConfidence]; an unconfirmed
 *   track switches at once;
 * - **no track**: a strong series, or the candidate agreeing with a confident YIN, starts one;
 * - the track is dropped after [maxGapSeconds] without a followed frame, so a new phrase
 *   starts fresh. Without any candidate the time-domain estimate stands on its own.
 */
class PitchTracker(
    private val comb: HarmonicCombRefiner,
    val glideOctaves: Double = 0.25,
    val nearScore: Double = 15.0,
    val strongScore: Double = 30.0,
    val strongCount: Int = 3,
    val jumpSeconds: Double = 0.35,
    val jumpRatio: Double = 1.5,
    /** A leap candidate may be absent this long without restarting its persistence timer. */
    val jumpGapSeconds: Double = 0.3,
    val confirmSeconds: Double = 0.25,
    val holdConfidence: Double = 0.7,
    val maxGapSeconds: Double = 0.5,
    val maxCandidates: Int = 5,
    /** With fewer counted harmonics the comb's refined frequency yields to an agreeing YIN. */
    val strongHarmonics: Int = 4,
    /** Score points added to the candidate agreeing with YIN, times YIN's confidence. */
    val yinBonus: Double = 25.0,
    val confidentYin: Double = 0.7,
    /** EMA factor of [trackStrength] per followed frame. */
    val strengthAlpha: Double = 0.3,
) {
    private val gridOut = Array(maxCandidates) { HarmonicCombRefiner.Candidate() }
    private val scratch = HarmonicCombRefiner.Candidate()

    /** Candidates of the last frame (debug / trace); [candidateCount] are valid. */
    val candidates = Array(maxCandidates + 3) { HarmonicCombRefiner.Candidate() }
    var candidateCount = 0
        private set
    /** Trace: why the YIN estimate itself was not a candidate (0 = it was). */
    var yinReject = 0
        private set

    var trackedHz: Double = 0.0
        private set
    /** Smoothed score of the followed candidates: how well founded the track is. */
    var trackStrength: Double = 0.0
        private set
    private var trackSince = Double.NaN
    private var lastGoodTime = Double.NEGATIVE_INFINITY
    private var jumpSince = Double.NaN
    private var jumpSeen = Double.NaN
    private var jumpTarget = 0.0

    /** Why the last frame was reported the way it was (trace). */
    var lastDecision: String = ""
        private set

    fun reset() {
        trackedHz = 0.0
        trackStrength = 0.0
        trackSince = Double.NaN
        lastGoodTime = Double.NEGATIVE_INFINITY
        jumpSince = Double.NaN
        jumpSeen = Double.NaN
    }

    private fun octaves(a: Double, b: Double): Double = abs(ln(a / b)) / ln(2.0)

    private fun isStrong(c: HarmonicCombRefiner.Candidate) = c.score >= strongScore && c.count >= strongCount

    private fun gather(yin: PitchEstimate, spectrum: PowerSpectrum, noise: NoiseFloor): Int {
        var n = comb.candidates(spectrum, noise, gridOut)
        for (i in 0 until n) candidates[i].set(gridOut[i])
        if (yin.found) {
            for (r in doubleArrayOf(1.0, 0.5, 2.0)) {
                val hz = yin.f0Hz * r
                var dup = false
                for (i in 0 until n) if (abs(candidates[i].hz - hz) / hz < 0.03) { dup = true; break }
                if (dup || n >= candidates.size) continue
                // the time-domain estimate itself needs no second harmonic (a pure tone)
                if (comb.scoreAt(hz, spectrum, noise, scratch, minLow = if (r == 1.0) 1 else comb.minLowHarmonics)) {
                    candidates[n].set(scratch); n++
                    if (r == 1.0) yinReject = 0
                } else if (r == 1.0) yinReject = comb.lastReject
            }
            for (i in 0 until n) if (abs(candidates[i].hz - yin.f0Hz) / yin.f0Hz < 0.03) candidates[i].score += yinBonus * yin.confidence
        }
        candidateCount = n
        return n
    }

    private fun follow(c: HarmonicCombRefiner.Candidate, yin: PitchEstimate, timeSec: Double, why: String): PitchEstimate {
        var hz = c.hz
        var conf = minOf(0.95, 0.5 + c.score / 80.0)
        if (yin.found && abs(yin.f0Hz - c.hz) / c.hz < 0.03) {
            // the two agree: the time-domain value is the finer one unless the comb refined
            // it from several clear harmonics
            conf = maxOf(conf, yin.confidence)
            if (c.count < strongHarmonics || yin.confidence >= 0.9) hz = yin.f0Hz
        }
        if (trackedHz <= 0.0 || octaves(hz, trackedHz) > glideOctaves) { trackSince = timeSec; trackStrength = c.score }
        else trackStrength += (c.score - trackStrength) * strengthAlpha
        trackedHz = hz
        lastGoodTime = timeSec
        jumpSince = Double.NaN
        lastDecision = why
        return PitchEstimate(hz, conf)
    }

    /** YIN agrees with the track while the comb sees nothing near: continuity carries it. */
    private fun followYin(yin: PitchEstimate, timeSec: Double): PitchEstimate {
        trackedHz = yin.f0Hz
        lastGoodTime = timeSec
        lastDecision = "yin"
        return PitchEstimate(yin.f0Hz, maxOf(yin.confidence, holdConfidence))
    }

    fun update(yin: PitchEstimate, spectrum: PowerSpectrum, noise: NoiseFloor, timeSec: Double): PitchEstimate {
        val tracking = trackedHz > 0.0 && timeSec - lastGoodTime <= maxGapSeconds
        if (!tracking && trackedHz > 0.0) reset()
        val confirmed = tracking && timeSec - trackSince >= confirmSeconds && trackStrength >= strongScore
        val n = gather(yin, spectrum, noise)
        val yinNear = tracking && yin.found && yin.confidence >= 0.5 && octaves(yin.f0Hz, trackedHz) <= glideOctaves
        // the time-domain estimate keeps a pending leap alive when the comb misses a frame
        if (!jumpSince.isNaN() && yin.found && yin.confidence >= 0.6 && octaves(yin.f0Hz, jumpTarget) <= glideOctaves) jumpSeen = timeSec

        if (n == 0) {
            lastDecision = "none"
            if (yinNear) return followYin(yin, timeSec)
            if (tracking && confirmed && yin.found && yin.confidence >= 0.5) {
                // a confident time-domain leap with no spectral support: hold, do not follow
                return PitchEstimate(trackedHz, holdConfidence)
            }
            return yin
        }

        // best overall and best near the track
        var best = 0
        for (i in 1 until n) if (candidates[i].score > candidates[best].score) best = i
        var near = -1
        if (tracking) {
            for (i in 0 until n) {
                if (octaves(candidates[i].hz, trackedHz) > glideOctaves) continue
                if (near < 0 || candidates[i].score > candidates[near].score) near = i
            }
            if (near >= 0 && candidates[near].score < nearScore) near = -1
        }

        if (!tracking) {
            val b = candidates[best]
            val yinAgrees = yin.found && yin.confidence >= confidentYin && abs(yin.f0Hz - b.hz) / b.hz < 0.03
            if (isStrong(b) || yinAgrees) return follow(b, yin, timeSec, "start")
            lastDecision = "weak"
            return if (yin.found && yin.confidence >= confidentYin) yin else PitchEstimate(b.hz, minOf(0.5, 0.3 + b.score / 80.0))
        }

        // leap bookkeeping: a strong series away from the track must persist
        val b = candidates[best]
        val far = best != near && octaves(b.hz, trackedHz) > glideOctaves && isStrong(b) && b.score >= jumpRatio * trackStrength && (near < 0 || b.score >= jumpRatio * candidates[near].score)
        if (far) {
            val cont = !jumpSince.isNaN() && octaves(b.hz, jumpTarget) <= glideOctaves && timeSec - jumpSeen <= jumpGapSeconds
            if (!cont) jumpSince = timeSec
            jumpSeen = timeSec
            jumpTarget = b.hz
            if (!confirmed || timeSec - jumpSince >= jumpSeconds) return follow(b, yin, timeSec, "leap")
        } else if (!jumpSince.isNaN() && timeSec - jumpSeen > jumpGapSeconds) {
            jumpSince = Double.NaN
        }

        if (near >= 0) return follow(candidates[near], yin, timeSec, "near")
        if (yinNear) return followYin(yin, timeSec)
        if (confirmed) {
            lastDecision = "hold"
            return PitchEstimate(trackedHz, holdConfidence)
        }
        lastDecision = "lost"
        return PitchEstimate(b.hz, minOf(0.5, 0.3 + b.score / 80.0))
    }
}

private fun HarmonicCombRefiner.Candidate.set(o: HarmonicCombRefiner.Candidate) { hz = o.hz; score = o.score; count = o.count }
