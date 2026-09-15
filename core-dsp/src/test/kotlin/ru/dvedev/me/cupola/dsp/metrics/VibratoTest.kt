package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.TestPipeline
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibratoTest {
    private val fs = 48_000

    private fun analyse(contour: PitchContour, seconds: Double = 4.0): List<Pair<Double, Vibrato>> {
        val p = TestPipeline(fs)
        val stats = PitchStats(p.hopSeconds)
        val vib = VibratoAnalyzer(p.hopSeconds)
        val out = mutableListOf<Pair<Double, Vibrato>>()
        p.run(Signals.harmonicVoice(VoiceSpec(contour), seconds, fs)) { s ->
            stats.push(if (s.pitch.confidence >= 0.7) s.pitch.f0Hz else 0.0)
            out += s.frame.endSeconds to vib.push(stats.residual)
        }
        return out
    }

    @Test
    fun `vibrato 5_5 Hz ±70 cents is measured and classified`() {
        val settled = analyse(PitchContour.Vibrato(220.0, 5.5, 70.0)).filter { it.first > 3.0 }.map { it.second }
        assertTrue(settled.isNotEmpty())
        for (v in settled) {
            assertEquals(5.5, v.rateHz, 0.3)
            assertEquals(70.0, v.extentCents, 10.0)
            assertEquals(VibratoKind.VIBRATO, v.kind)
        }
    }

    @Test
    fun `straight tone`() {
        val settled = analyse(PitchContour.Constant(300.0)).filter { it.first > 3.0 }.map { it.second }
        assertTrue(settled.all { it.kind == VibratoKind.STRAIGHT }, "kinds ${settled.map { it.kind }.distinct()}")
        assertTrue(settled.all { it.extentCents < 15.0 })
    }

    @Test
    fun `wobble 3 Hz ±150 cents`() {
        val settled = analyse(PitchContour.Vibrato(220.0, 3.0, 150.0)).filter { it.first > 3.0 }.map { it.second }
        for (v in settled) {
            assertEquals(3.0, v.rateHz, 0.4)
            assertEquals(VibratoKind.WOBBLE, v.kind, "rate ${v.rateHz} extent ${v.extentCents}")
        }
    }

    @Test
    fun `tremolo 9 Hz ±40 cents`() {
        val settled = analyse(PitchContour.Vibrato(300.0, 9.0, 40.0)).filter { it.first > 3.0 }.map { it.second }
        for (v in settled) {
            assertEquals(9.0, v.rateHz, 0.4)
            assertEquals(VibratoKind.TREMOLO, v.kind)
        }
    }

    @Test
    fun `classification boundaries`() {
        assertEquals(VibratoKind.STRAIGHT, VibratoAnalyzer.classify(5.5, 10.0))
        assertEquals(VibratoKind.VIBRATO, VibratoAnalyzer.classify(4.5, 70.0))
        assertEquals(VibratoKind.VIBRATO, VibratoAnalyzer.classify(7.0, 120.0))
        assertEquals(VibratoKind.WOBBLE, VibratoAnalyzer.classify(3.9, 70.0))
        assertEquals(VibratoKind.WOBBLE, VibratoAnalyzer.classify(5.5, 130.0))
        assertEquals(VibratoKind.TREMOLO, VibratoAnalyzer.classify(8.0, 30.0))
    }
}
