package ru.dvedev.me.cupola.dsp.calibration

import ru.dvedev.me.cupola.dsp.metrics.RingBand
import kotlin.math.roundToInt

enum class CalibrationPhase { SILENCE, VOWEL, DONE }

enum class CalibrationWarning {
    /** Silence phase louder than −45 dBFS: results will be less reliable. */
    NOISY_ROOM,

    /** Fewer than half of the /a/ frames were confidently voiced: repeat. */
    UNSTABLE_VOICE,
}

data class CalibrationResult(
    val calibration: Calibration?,
    val warnings: Set<CalibrationWarning>,
) {
    /** True when no usable baseline came out and the procedure has to be repeated. */
    val mustRepeat: Boolean get() = calibration == null
}

/**
 * Two-phase baseline procedure: [silenceSeconds] of silence → noise floor, then
 * [vowelSeconds] of /a/ at a comfortable pitch and volume → ring and SPL baselines.
 * Feed one [Input] per analysis frame; medians make the result robust to a cough or a
 * late start.
 */
class CalibrationSession(
    val hopSeconds: Double,
    val band: RingBand,
    val silenceSeconds: Double = 2.0,
    val vowelSeconds: Double = 4.0,
    val confidenceMin: Double = 0.7,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Input(val rmsDbfs: Double, val ringRatioDb: Double, val splDbfs: Double, val confidence: Double, val voice: Boolean)

    private val silenceFrames = (silenceSeconds / hopSeconds).roundToInt()
    private val vowelFrames = (vowelSeconds / hopSeconds).roundToInt()
    private val silenceRms = ArrayList<Double>(silenceFrames)
    private val vowelRing = ArrayList<Double>(vowelFrames)
    private val vowelSpl = ArrayList<Double>(vowelFrames)
    private var vowelTotal = 0

    var phase: CalibrationPhase = CalibrationPhase.SILENCE
        private set

    /** 0..1 within the current phase. */
    val progress: Double
        get() = when (phase) {
            CalibrationPhase.SILENCE -> silenceRms.size.toDouble() / silenceFrames
            CalibrationPhase.VOWEL -> vowelTotal.toDouble() / vowelFrames
            CalibrationPhase.DONE -> 1.0
        }

    val secondsLeft: Double
        get() = when (phase) {
            CalibrationPhase.SILENCE -> (silenceFrames - silenceRms.size) * hopSeconds
            CalibrationPhase.VOWEL -> (vowelFrames - vowelTotal) * hopSeconds
            CalibrationPhase.DONE -> 0.0
        }

    fun push(input: Input) {
        when (phase) {
            CalibrationPhase.SILENCE -> {
                silenceRms += input.rmsDbfs
                if (silenceRms.size >= silenceFrames) phase = CalibrationPhase.VOWEL
            }
            CalibrationPhase.VOWEL -> {
                vowelTotal++
                if (input.voice && input.confidence >= confidenceMin) {
                    vowelRing += input.ringRatioDb
                    vowelSpl += input.splDbfs
                }
                if (vowelTotal >= vowelFrames) phase = CalibrationPhase.DONE
            }
            CalibrationPhase.DONE -> Unit
        }
    }

    fun result(): CalibrationResult {
        check(phase == CalibrationPhase.DONE) { "calibration not finished" }
        val warnings = mutableSetOf<CalibrationWarning>()
        val noise = median(silenceRms)
        if (noise > NOISY_ROOM_DBFS) warnings += CalibrationWarning.NOISY_ROOM
        val voicedShare = vowelRing.size.toDouble() / vowelTotal
        if (voicedShare < MIN_VOICED_SHARE) {
            warnings += CalibrationWarning.UNSTABLE_VOICE
            return CalibrationResult(null, warnings)
        }
        return CalibrationResult(
            Calibration(
                band = band,
                noiseFloorDbfs = noise,
                ringRatioDb = median(vowelRing),
                splDbfs = median(vowelSpl),
                voicedShare = voicedShare,
                createdAtEpochMs = now(),
            ),
            warnings,
        )
    }

    fun reset() {
        silenceRms.clear()
        vowelRing.clear()
        vowelSpl.clear()
        vowelTotal = 0
        phase = CalibrationPhase.SILENCE
    }

    companion object {
        const val NOISY_ROOM_DBFS = -45.0
        const val MIN_VOICED_SHARE = 0.5

        internal fun median(values: List<Double>): Double {
            if (values.isEmpty()) return Double.NaN
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
        }
    }
}
