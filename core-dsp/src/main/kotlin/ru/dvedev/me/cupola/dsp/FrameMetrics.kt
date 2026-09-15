package ru.dvedev.me.cupola.dsp

import ru.dvedev.me.cupola.dsp.metrics.Harmonic
import ru.dvedev.me.cupola.dsp.metrics.Vibrato
import ru.dvedev.me.cupola.dsp.score.Gate
import ru.dvedev.me.cupola.notation.Note

/**
 * Everything the UI needs from one analysis frame (SPEC §4: published at 100 Hz).
 * Immutable; the spectrogram column is delivered separately to avoid copying 1025
 * doubles per frame.
 */
data class FrameMetrics(
    /** End of the frame, seconds since the stream started. */
    val timeSec: Double,
    val voice: Boolean,
    val f0Hz: Double,
    val confidence: Double,
    /** Nearest equal-tempered note (only meaningful when [voiced]). */
    val note: Note,
    /** Deviation from [note], cents in (−50, +50]; NaN when unvoiced. */
    val cents: Double,
    val splDbfs: Double,
    val noiseFloorDbfs: Double,
    val ringRatioDb: Double,
    /** Share of the voice energy (room noise subtracted) in the cupola band, %. */
    val ringSharePct: Double,
    val peakSprDb: Double,
    /** Band peak over its flanks, dB: > 0 is a hump. */
    val humpDb: Double,
    /** Median-smoothed (300 ms) count of audible harmonics `k ≥ 2`. */
    val overtoneCount: Int,
    val harmonics: List<Harmonic>,
    val pitchSd: Double,
    val driftCentsPerSec: Double,
    val vibrato: Vibrato,
    val gate: Gate,
    val ring: Double,
    val pitch: Double,
    val steady: Double,
    val score: Double,
    val streakSeconds: Double,
) {
    /** Voice present and pitch trusted (`confidence ≥ 0.7`). */
    val voiced: Boolean get() = voice && gate != Gate.LOW_CONFIDENCE && f0Hz > 0
}
