package ru.dvedev.me.cupola.dsp.pitch

/**
 * Result of one pitch estimate. [f0Hz] is 0 when nothing periodic was found;
 * [confidence] is in `0..1` (for YIN: `1 − CMND(τ)`). The voicing decision
 * (`confidence ≥ 0.7`, SPEC §5.1) belongs to the caller.
 */
data class PitchEstimate(val f0Hz: Double, val confidence: Double) {
    val found: Boolean get() = f0Hz > 0.0

    companion object {
        val NONE = PitchEstimate(0.0, 0.0)
    }
}

/** Pitch detector over one unwindowed analysis frame. Implementations are stateless per frame. */
interface PitchDetector {
    val minHz: Double
    val maxHz: Double

    fun estimate(frame: DoubleArray, sampleRate: Int): PitchEstimate
}
