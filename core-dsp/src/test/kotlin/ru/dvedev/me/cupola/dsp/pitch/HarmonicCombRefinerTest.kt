package ru.dvedev.me.cupola.dsp.pitch

import ru.dvedev.me.cupola.dsp.Analyzer
import ru.dvedev.me.cupola.dsp.AnalyzerConfig
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

class HarmonicCombRefinerTest {
    private val fs = 48_000

    private fun cents(a: Double, b: Double) = 1200.0 * ln(a / b) / ln(2.0)

    /** Runs 1 s of room noise then [signal]; returns metrics after t > 1.5 s. */
    private fun run(signal: DoubleArray): List<FrameMetrics> {
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = fs))
        val input = Signals.mix(Signals.whiteNoise(1.0 + signal.size.toDouble() / fs, fs, rms = 0.0005), Signals.concat(Signals.silence(1.0, fs), signal))
        val out = mutableListOf<FrameMetrics>()
        analyzer.push(input) { m, _ -> if (m.timeSec > 1.5) out += m }
        return out
    }

    @Test
    fun `voice over a louder bass line is reported at the voice pitch`() {
        // tenor G4 (392 Hz, rich) + a bass sine at G2 (98 Hz) 6 dB louder — YIN alone locks on 98 Hz
        val voice = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(392.0), tiltDbPerOctave = -9.0, amplitude = 0.15, normalise = false), 1.5, fs)
        val bass = Signals.sine(98.0, 1.5, fs, amplitude = 0.3)
        val frames = run(Signals.mix(voice, bass))
        val voiced = frames.filter { it.voiced }
        assertTrue(voiced.size > frames.size * 0.8, "voiced ${voiced.size} of ${frames.size}")
        val atVoice = voiced.count { abs(cents(it.f0Hz, 392.0)) < 30 }
        assertTrue(atVoice > voiced.size * 0.9, "at 392 Hz: $atVoice of ${voiced.size}, sample f0s ${voiced.take(5).map { it.f0Hz }}")
    }

    @Test
    fun `rich low note keeps its fundamental and a pure sine keeps its pitch`() {
        for ((hz, spec) in listOf(
            82.41 to VoiceSpec(PitchContour.Constant(82.41), tiltDbPerOctave = -9.0, maxHarmonics = 40),
            440.0 to VoiceSpec(PitchContour.Constant(440.0), maxHarmonics = 1),
            1046.5 to VoiceSpec(PitchContour.Constant(1046.5), tiltDbPerOctave = -9.0),
        )) {
            val frames = run(Signals.harmonicVoice(spec, 1.0, fs)).filter { it.voiced }
            assertTrue(frames.isNotEmpty(), "$hz: no voiced frames")
            for (m in frames) assertTrue(abs(cents(m.f0Hz, hz)) < 10, "$hz Hz → ${m.f0Hz}")
        }
    }
}
