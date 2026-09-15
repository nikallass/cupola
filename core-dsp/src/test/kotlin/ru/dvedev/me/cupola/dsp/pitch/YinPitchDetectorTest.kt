package ru.dvedev.me.cupola.dsp.pitch

import ru.dvedev.me.cupola.dsp.frame.Framer
import ru.dvedev.me.cupola.testdata.Formant
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YinPitchDetectorTest {
    private val fs = 48_000
    private val yin = YinPitchDetector(2048)

    private fun cents(a: Double, b: Double) = 1200.0 * ln(a / b) / ln(2.0)

    private fun estimates(signal: DoubleArray): List<Pair<Double, PitchEstimate>> {
        val framer = Framer(2048, 480, fs)
        val out = mutableListOf<Pair<Double, PitchEstimate>>()
        framer.push(signal) { f -> out += f.centerSeconds to yin.estimate(f.raw, fs) }
        return out
    }

    @Test
    fun `220 Hz sine within 0_5 Hz and high confidence`() {
        for ((_, e) in estimates(Signals.sine(220.0, 0.5, fs))) {
            assertEquals(220.0, e.f0Hz, 0.5)
            assertTrue(e.confidence > 0.95, "confidence ${e.confidence}")
        }
    }

    @Test
    fun `no octave errors at E2 and C6 on harmonic voices`() {
        for (hz in listOf(82.41, 1046.5)) {
            val voice = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(hz), tiltDbPerOctave = -9.0), 0.5, fs)
            for ((t, e) in estimates(voice)) {
                assertTrue(e.found, "$hz Hz at $t s: not found")
                assertTrue(abs(cents(e.f0Hz, hz)) < 5.0, "$hz Hz at $t s: got ${e.f0Hz}")
            }
        }
    }

    @Test
    fun `formant-rich vowel stays within 5 cents`() {
        val vowel = VoiceSpec(
            PitchContour.Constant(196.0),
            tiltDbPerOctave = -12.0,
            formants = listOf(Formant(700.0, 200.0, 18.0), Formant(1200.0, 250.0, 12.0), Formant(2800.0, 500.0, 15.0)),
        )
        for ((t, e) in estimates(Signals.harmonicVoice(vowel, 0.5, fs))) {
            assertTrue(abs(cents(e.f0Hz, 196.0)) < 5.0, "at $t s: ${e.f0Hz}")
            assertTrue(e.confidence >= 0.7)
        }
    }

    @Test
    fun `white noise gives low confidence`() {
        for ((_, e) in estimates(Signals.whiteNoise(0.5, fs, rms = 0.2))) {
            assertTrue(e.confidence < 0.5, "confidence ${e.confidence}")
        }
    }

    @Test
    fun `silence gives no pitch`() {
        for ((_, e) in estimates(Signals.silence(0.2, fs))) {
            assertEquals(PitchEstimate.NONE, e)
        }
    }

    @Test
    fun `glissando is tracked`() {
        val contour = PitchContour.Glissando(200.0, 400.0) // +400 ¢/s over 2 s → 200 → 317 Hz
        val voice = Signals.harmonicVoice(VoiceSpec(contour), 2.0, fs)
        for ((t, e) in estimates(voice)) {
            val expected = contour.hzAt(t)
            assertTrue(abs(cents(e.f0Hz, expected)) < 15.0, "at $t s expected $expected got ${e.f0Hz}")
        }
    }

    @Test
    fun `estimate latency below 5 ms`() {
        val frame = Signals.sine(220.0, 2048.0 / fs, fs)
        repeat(200) { yin.estimate(frame, fs) }
        val iterations = 300
        val t0 = System.nanoTime()
        repeat(iterations) { yin.estimate(frame, fs) }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / iterations
        println("YIN(2048): %.3f ms per call".format(perCallMs))
        assertTrue(perCallMs < 5.0, "too slow: $perCallMs ms")
    }
}
