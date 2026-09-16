package ru.dvedev.me.cupola.dsp

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.frame.Framer
import ru.dvedev.me.cupola.dsp.metrics.HarmonicTracker
import ru.dvedev.me.cupola.dsp.metrics.NoiseFloor
import ru.dvedev.me.cupola.dsp.metrics.PitchStats
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.dsp.metrics.RingMetrics
import ru.dvedev.me.cupola.dsp.metrics.VibratoAnalyzer
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.dsp.pitch.HarmonicCombRefiner
import ru.dvedev.me.cupola.dsp.pitch.PitchDetector
import ru.dvedev.me.cupola.dsp.pitch.PitchTracker
import ru.dvedev.me.cupola.dsp.pitch.YinPitchDetector
import ru.dvedev.me.cupola.dsp.score.Gate
import ru.dvedev.me.cupola.dsp.score.ScoreInput
import ru.dvedev.me.cupola.dsp.score.ScoreParams
import ru.dvedev.me.cupola.dsp.score.Scorer
import ru.dvedev.me.cupola.dsp.util.RollingMedian
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.notation.Tuning
import ru.dvedev.me.cupola.notation.nearestNote

data class AnalyzerConfig(
    val sampleRate: Int = AudioFormatDefaults.SAMPLE_RATE,
    val fftSize: Int = AudioFormatDefaults.FFT_SIZE,
    val hop: Int = AudioFormatDefaults.HOP_SIZE,
    val band: RingBand = VoiceType.UNSET.band,
    val a4Hz: Double = Tuning.DEFAULT_A4_HZ,
    val confidenceMin: Double = 0.7,
    val includeFundamentalInOvertones: Boolean = false,
    val vibratoThresholds: ru.dvedev.me.cupola.dsp.metrics.VibratoThresholds = ru.dvedev.me.cupola.dsp.metrics.VibratoThresholds(),
    /** Quiet time the adaptive noise profile covers, seconds. */
    val noiseWindowSeconds: Double = ru.dvedev.me.cupola.dsp.metrics.NoiseFloor.DEFAULT_WINDOW_SECONDS,
    val scoreParams: ScoreParams = ScoreParams(),
)

/**
 * The whole SPEC §4 pipeline for one audio stream: samples → frames → spectrum → pitch →
 * harmonics → metrics → gates/score → [FrameMetrics]. Single-threaded: call [push] from
 * the analysis thread only. Runtime-changeable settings are `var`s.
 */
class Analyzer(config: AnalyzerConfig, pitchDetector: PitchDetector? = null) {
    val sampleRate = config.sampleRate
    val fftSize = config.fftSize
    val hop = config.hop
    val hopSeconds: Double = hop.toDouble() / sampleRate

    @Volatile var band: RingBand = config.band
    @Volatile var a4Hz: Double = config.a4Hz
    @Volatile var confidenceMin: Double = config.confidenceMin
    @Volatile var includeFundamentalInOvertones: Boolean = config.includeFundamentalInOvertones
    var vibratoThresholds: ru.dvedev.me.cupola.dsp.metrics.VibratoThresholds
        get() = vibrato.thresholds
        set(value) { vibrato.thresholds = value }

    val framer = Framer(fftSize, hop, sampleRate)
    /** Live spectrum of the frame being reported in the callback (dB per bin). */
    val spectrum = PowerSpectrum(fftSize, sampleRate)
    val pitchDetector: PitchDetector = pitchDetector ?: YinPitchDetector(fftSize)
    val noise = NoiseFloor(spectrum.bins, hopSeconds, windowSeconds = config.noiseWindowSeconds)
    val combRefiner = HarmonicCombRefiner()
    val pitchTracker = PitchTracker(combRefiner)
    /** Debug hook: raw time-domain estimate and the tracked result of every voiced frame. */
    @Volatile var pitchTrace: ((raw: ru.dvedev.me.cupola.dsp.pitch.PitchEstimate, out: ru.dvedev.me.cupola.dsp.pitch.PitchEstimate) -> Unit)? = null
    val harmonics = HarmonicTracker(maxHz = minOf(8000.0, spectrum.nyquistHz))
    val pitchStats = PitchStats(hopSeconds)
    val vibrato = VibratoAnalyzer(hopSeconds).also { it.thresholds = config.vibratoThresholds }
    val scorer = Scorer(hopSeconds, config.scoreParams)
    private val overtoneMedian = RollingMedian((0.3 / hopSeconds).toInt().coerceAtLeast(1))
    private val noteScratch = Note(69)

    /** Feed PCM floats (mono, ±1). [onFrame] runs once per hop with the metrics and the live spectrum. */
    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset, onFrame: (FrameMetrics, PowerSpectrum) -> Unit) {
        framer.push(samples, offset, length) { f -> onFrame(analyse(f.raw, f.windowed, f.endSeconds), spectrum) }
    }

    fun push(samples: DoubleArray, offset: Int = 0, length: Int = samples.size - offset, onFrame: (FrameMetrics, PowerSpectrum) -> Unit) {
        framer.push(samples, offset, length) { f -> onFrame(analyse(f.raw, f.windowed, f.endSeconds), spectrum) }
    }

    private fun analyse(raw: DoubleArray, windowed: DoubleArray, timeSec: Double): FrameMetrics {
        spectrum.compute(windowed)
        val spl = RingMetrics.splDbfs(raw)
        val voice = noise.update(spectrum.db, spl)
        val raw0 = if (voice) pitchDetector.estimate(raw, sampleRate) else ru.dvedev.me.cupola.dsp.pitch.PitchEstimate.NONE
        // spectral tracking (PitchTracker): harmonic-comb candidates + YIN octaves, continuity
        // penalty, delayed jumps — fixes octave / bass-line slips on real recordings
        val pitch = if (voice && noise.initialized) pitchTracker.update(raw0, spectrum, noise, timeSec) else raw0
        pitchTrace?.invoke(raw0, pitch)
        val trusted = voice && pitch.found && pitch.confidence >= confidenceMin
        val f0 = if (trusted) pitch.f0Hz else 0.0

        val nearest = if (trusted) nearestNote(f0, a4Hz) else null
        val cents = nearest?.cents ?: Double.NaN
        val note = nearest?.note ?: noteScratch

        val ringMeasure = RingMetrics.measure(spectrum, band, spl, if (noise.initialized) noise else null)

        val hset = harmonics.track(f0, spectrum, noise)
        overtoneMedian.push(if (trusted) hset.overtoneCount(includeFundamentalInOvertones).toDouble() else Double.NaN)
        val overtoneCount = if (trusted) overtoneMedian.median().let { if (it.isNaN()) 0 else it.toInt() } else 0

        pitchStats.push(f0)
        val vib = vibrato.push(pitchStats.residual)

        val score = scorer.frame(
            ScoreInput(
                voice = voice,
                confidence = if (voice) pitch.confidence else 0.0,
                cents = cents,
                pitchSd = pitchStats.pitchSd,
                vibratoKind = vib.kind,
                ringSharePct = ringMeasure.ringSharePct,
                humpDb = ringMeasure.humpDb,
            ),
        )
        return FrameMetrics(
            timeSec = timeSec,
            voice = voice,
            f0Hz = f0,
            confidence = if (voice) pitch.confidence else 0.0,
            note = note,
            cents = cents,
            splDbfs = spl,
            noiseFloorDbfs = noise.rmsFloorDb,
            ringRatioDb = ringMeasure.ringRatioDb,
            ringSharePct = ringMeasure.ringSharePct,
            peakSprDb = ringMeasure.peakSprDb,
            humpDb = ringMeasure.humpDb,
            overtoneCount = overtoneCount,
            harmonics = if (trusted) hset.snapshot() else emptyList(),
            pitchSd = pitchStats.pitchSd,
            driftCentsPerSec = pitchStats.drift,
            vibrato = vib,
            gate = score.gate,
            ring = score.ring,
            pitch = score.pitch,
            steady = score.steady,
            score = score.score,
            streakSeconds = score.streakSeconds,
        )
    }

    /** Forget contour and smoother state (new phrase / after a pause); keeps the noise floor. */
    fun resetContour() {
        pitchTracker.reset()
        pitchStats.reset()
        vibrato.reset()
        scorer.reset()
        overtoneMedian.clear()
    }

    fun resetAll() {
        framer.reset()
        noise.reset()
        resetContour()
    }
}

/** Convenience for gate checks in UI code. */
val FrameMetrics.ringCounted: Boolean get() = gate == Gate.OPEN
