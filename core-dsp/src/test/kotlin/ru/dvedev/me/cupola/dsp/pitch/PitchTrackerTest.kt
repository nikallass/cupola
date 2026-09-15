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

/** Owner rule: a confidently found note must survive a burst of octave-hopping estimates. */
class PitchTrackerTest {
    private val fs = 48_000

    private fun cents(a: Double, b: Double) = 1200.0 * ln(a / b) / ln(2.0)

    private fun run(signal: DoubleArray): List<FrameMetrics> {
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = fs))
        val input = Signals.mix(Signals.whiteNoise(1.0 + signal.size.toDouble() / fs, fs, rms = 0.0005), Signals.concat(Signals.silence(1.0, fs), signal))
        val out = mutableListOf<FrameMetrics>()
        analyzer.push(input) { m, _ -> if (m.timeSec > 1.5) out += m }
        return out
    }

    @Test
    fun `a sung note with vibrato survives short louder bursts an octave and a fifth away`() {
        // baritone A3 with 6 Hz / ±60 ¢ vibrato for 3 s; every 0.5 s a 120 ms burst of a
        // louder harmonic tone appears at A2, E3 or A4 (orchestral hits)
        val voice = Signals.harmonicVoice(VoiceSpec(PitchContour.Vibrato(220.0, 6.0, 60.0), tiltDbPerOctave = -9.0, amplitude = 0.2, normalise = false), 3.0, fs)
        val bursts = DoubleArray(voice.size)
        val hits = doubleArrayOf(110.0, 164.8, 440.0, 110.0, 440.0)
        for ((i, hz) in hits.withIndex()) {
            val start = ((0.4 + 0.5 * i) * fs).toInt()
            val tone = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(hz), tiltDbPerOctave = -6.0, amplitude = 0.4, normalise = false), 0.12, fs)
            for (j in tone.indices) if (start + j < bursts.size) bursts[start + j] += tone[j]
        }
        val frames = run(Signals.mix(voice, bursts)).filter { it.voiced }
        assertTrue(frames.size > 200, "voiced frames: ${frames.size}")
        val off = frames.count { abs(cents(it.f0Hz, 220.0)) > 150 }
        assertTrue(off <= frames.size / 20, "frames off the note: $off of ${frames.size}")
    }

    @Test
    fun `a real change of note is followed within half a second`() {
        val a = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0), tiltDbPerOctave = -9.0), 1.0, fs)
        val b = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(329.6), tiltDbPerOctave = -9.0), 1.0, fs)
        val frames = run(Signals.concat(a, b)).filter { it.voiced }
        // signal starts at t = 1.0 s, the E4 at t = 2.0 s
        val late = frames.filter { it.timeSec > 2.5 }
        assertTrue(late.isNotEmpty())
        val wrong = late.count { abs(cents(it.f0Hz, 329.6)) > 50 }
        assertTrue(wrong <= late.size / 20, "frames not on E4 after the change: $wrong of ${late.size}")
    }
}
