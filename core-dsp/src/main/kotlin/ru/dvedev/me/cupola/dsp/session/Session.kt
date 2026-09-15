package ru.dvedev.me.cupola.dsp.session

import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.score.Gate

/** Decimated (10 Hz) record of a session frame — what a saved session keeps per tick. */
data class SessionFrame(
    val t: Double,
    val f0Hz: Double,
    val cents: Double,
    val ringSharePct: Double,
    val humpDb: Double,
    val splDbfs: Double,
    val score: Double,
    val gate: Gate,
)

data class SessionSummary(
    val durationSec: Double,
    val voicedSec: Double,
    val meanScore: Double,
    val bestScore: Double,
    /** Share of voiced time with `score ≥ 0.7`. */
    val shareAbove07: Double,
    val points: Int,
    val bestStreakSec: Double,
    val meanRing: Double,
    val meanPitch: Double,
    val meanSteady: Double,
    val frames: Int,
) {
    companion object {
        val EMPTY = SessionSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0)
    }
}

/** A finished session: summary + decimated frames + optional audio file (recording is backlog). */
data class SessionRecording(
    val startedAtEpochMs: Long,
    val summary: SessionSummary,
    val frames: List<SessionFrame>,
    val wavPath: String? = null,
)

/**
 * Accumulates [FrameMetrics] of one session: points, streaks, running means and a
 * 10 Hz frame log. Means are over voiced frames only, so pauses do not dilute them.
 */
class SessionAccumulator(
    val hopSeconds: Double,
    val decimation: Int = 10,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val points = PointsCounter()
    private val log = ArrayList<SessionFrame>()
    private var frames = 0
    private var voicedFrames = 0
    private var sumScore = 0.0
    private var sumRing = 0.0
    private var sumPitch = 0.0
    private var sumSteady = 0.0
    private var above07 = 0
    private var best = 0.0
    private var bestStreak = 0.0
    private var firstTime = Double.NaN
    private var lastTime = Double.NaN

    /** Feed one frame; returns the points awarded on this frame (0 or a portion). */
    fun add(m: FrameMetrics): Int {
        if (firstTime.isNaN()) firstTime = m.timeSec
        lastTime = m.timeSec
        frames++
        if (m.voice) {
            voicedFrames++
            sumScore += m.score
            sumRing += m.ring
            sumPitch += m.pitch
            sumSteady += m.steady
            if (m.score >= 0.7) above07++
        }
        if (m.score > best) best = m.score
        if (m.streakSeconds > bestStreak) bestStreak = m.streakSeconds
        if (frames % decimation == 1 || decimation == 1) {
            log += SessionFrame(m.timeSec, m.f0Hz, m.cents, m.ringSharePct, m.humpDb, m.splDbfs, m.score, m.gate)
        }
        return points.update(m.timeSec, m.ring)
    }

    val frameLog: List<SessionFrame> get() = log

    fun summary(): SessionSummary {
        if (frames == 0) return SessionSummary.EMPTY
        val v = voicedFrames.coerceAtLeast(1).toDouble()
        return SessionSummary(
            durationSec = if (firstTime.isNaN()) 0.0 else lastTime - firstTime + hopSeconds,
            voicedSec = voicedFrames * hopSeconds,
            meanScore = if (voicedFrames == 0) 0.0 else sumScore / v,
            bestScore = best,
            shareAbove07 = if (voicedFrames == 0) 0.0 else above07 / v,
            points = points.total,
            bestStreakSec = bestStreak,
            meanRing = if (voicedFrames == 0) 0.0 else sumRing / v,
            meanPitch = if (voicedFrames == 0) 0.0 else sumPitch / v,
            meanSteady = if (voicedFrames == 0) 0.0 else sumSteady / v,
            frames = frames,
        )
    }

    fun recording(wavPath: String? = null): SessionRecording = SessionRecording(startedAtEpochMs, summary(), log.toList(), wavPath)
}
