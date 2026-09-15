package ru.dvedev.me.cupola.dsp.score

import ru.dvedev.me.cupola.dsp.calibration.Calibration
import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import kotlin.math.abs

/**
 * Weights of the v0.1 score (SPEC §15.5). This is the single place they live; the
 * `clean`/CPPS indicator joins with the advanced panel and the table changes here.
 */
object ScoreWeights {
    const val RING = 0.6
    const val PITCH = 0.25
    const val STEADY = 0.15
}

/** Why the ring indicator is not being counted this frame (SPEC §6.2). Order = hint priority. */
enum class Gate {
    OPEN,
    NO_VOICE,
    LOW_CONFIDENCE,
    /** `SPL > SPL_baseline + 12 dB` — «Громче ≠ звонче». Score is 0. */
    PUSHED,
    /** `pitchSD > 20 ¢` — «Нота плывёт». */
    UNSTABLE_PITCH,
    /** SOVT warm-up: ring is undefined by construction. Not selectable in v0.1. */
    SOVT,
    /** No calibration yet: ring cannot be related to anything. */
    NOT_CALIBRATED,
}

data class ScoreParams(
    /** dB of `RingRatio_norm` above baseline that counts as full ring. */
    val targetGainDb: Double = 6.0,
    val confidenceMin: Double = 0.7,
    val pitchSdMaxCents: Double = 20.0,
    val pushedMarginDb: Double = 12.0,
    val attackSeconds: Double = 0.08,
    val releaseSeconds: Double = 0.4,
    val streakScore: Double = 0.7,
    val streakSeconds: Double = 3.0,
    /** Multiplier on `steady` while the modulation is a wobble or a tremolo (SPEC §15.5). */
    val wobbleSteadyFactor: Double = 0.6,
    /** Full pitch credit up to this deviation … */
    val pitchFullCents: Double = 5.0,
    /** … falling to zero over this many further cents. */
    val pitchRampCents: Double = 25.0,
    /** `steady = 1 − pitchSD / this`. */
    val steadySdCents: Double = 30.0,
)

data class ScoreInput(
    val voice: Boolean,
    val confidence: Double,
    /** Deviation from the nearest note, cents; NaN when unvoiced. */
    val cents: Double,
    /** SD of the median line over 500 ms; NaN when unknown. */
    val pitchSd: Double,
    val vibratoKind: VibratoKind,
    /** `RingRatio_norm` of this frame. */
    val ringRatioNorm: Double,
    val splDbfs: Double,
    val sovt: Boolean = false,
)

data class ScoreOutput(
    val gate: Gate,
    val ringRaw: Double,
    val pitchRaw: Double,
    val steadyRaw: Double,
    /** Smoothed indicators (attack 80 ms / release 400 ms) and their weighted sum. */
    val ring: Double,
    val pitch: Double,
    val steady: Double,
    val score: Double,
    /** Seconds of continuous `score ≥ 0.7`. */
    val streakSeconds: Double,
) {
    val inStreak: Boolean get() = streakSeconds > 0.0
}

/**
 * Gates and indicators of SPEC §6.2 / §15.5. Evaluate once per frame with [frame];
 * the smoothers keep state between calls.
 */
class Scorer(
    val hopSeconds: Double,
    calibration: Calibration?,
    val params: ScoreParams = ScoreParams(),
) {
    var calibration: Calibration? = calibration
    private val ringSmooth = AttackRelease(params.attackSeconds, params.releaseSeconds, hopSeconds)
    private val pitchSmooth = AttackRelease(params.attackSeconds, params.releaseSeconds, hopSeconds)
    private val steadySmooth = AttackRelease(params.attackSeconds, params.releaseSeconds, hopSeconds)
    private var streak = 0.0

    fun frame(input: ScoreInput): ScoreOutput {
        val base = calibration
        val gate = when {
            !input.voice -> Gate.NO_VOICE
            input.confidence < params.confidenceMin -> Gate.LOW_CONFIDENCE
            input.sovt -> Gate.SOVT
            base == null -> Gate.NOT_CALIBRATED
            input.splDbfs > base.splDbfs + params.pushedMarginDb -> Gate.PUSHED
            input.pitchSd.isNaN() || input.pitchSd > params.pitchSdMaxCents -> Gate.UNSTABLE_PITCH
            else -> Gate.OPEN
        }
        val voiced = gate != Gate.NO_VOICE && gate != Gate.LOW_CONFIDENCE
        val pushed = gate == Gate.PUSHED

        val ringRaw = if (gate == Gate.OPEN && base != null) {
            ((input.ringRatioNorm - base.ringRatioDb) / params.targetGainDb).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val pitchRaw = if (voiced && !pushed && !input.cents.isNaN()) {
            1.0 - ((abs(input.cents) - params.pitchFullCents) / params.pitchRampCents).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val steadyRaw = if (voiced && !pushed && !input.pitchSd.isNaN()) {
            val s = 1.0 - (input.pitchSd / params.steadySdCents).coerceIn(0.0, 1.0)
            if (input.vibratoKind == VibratoKind.WOBBLE || input.vibratoKind == VibratoKind.TREMOLO) s * params.wobbleSteadyFactor else s
        } else {
            0.0
        }

        val ring = ringSmooth.process(ringRaw)
        val pitch = pitchSmooth.process(pitchRaw)
        val steady = steadySmooth.process(steadyRaw)
        val score = if (pushed) 0.0 else ScoreWeights.RING * ring + ScoreWeights.PITCH * pitch + ScoreWeights.STEADY * steady

        streak = if (score >= params.streakScore) streak + hopSeconds else 0.0
        val streakOut = if (streak >= params.streakSeconds) streak else 0.0
        return ScoreOutput(gate, ringRaw, pitchRaw, steadyRaw, ring, pitch, steady, score, streakOut)
    }

    fun reset() {
        ringSmooth.reset()
        pitchSmooth.reset()
        steadySmooth.reset()
        streak = 0.0
    }
}
