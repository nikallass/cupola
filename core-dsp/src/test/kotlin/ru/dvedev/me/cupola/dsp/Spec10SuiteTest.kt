package ru.dvedev.me.cupola.dsp

import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.dsp.score.Gate
import ru.dvedev.me.cupola.notation.NoteNames
import ru.dvedev.me.cupola.testdata.BandGain
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §10 end-to-end through [Analyzer]: every case below is also covered by a unit test
 * of the component, this suite checks that the wiring keeps them true.
 */
class Spec10SuiteTest {
    private val fs = 48_000
    private val room = 0.0005 // −66 dBFS white noise so the noise floor initialises

    private fun run(signal: DoubleArray, block: (FrameMetrics) -> Unit) {
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = fs))
        val input = Signals.mix(Signals.whiteNoise(1.0 + signal.size.toDouble() / fs, fs, rms = room), Signals.concat(Signals.silence(1.0, fs), signal))
        analyzer.push(input) { m, _ -> if (m.timeSec > 1.5) block(m) }
    }

    @Test
    fun `sine 220 Hz is A3 within 1 cent, 226 Hz is +46`() {
        run(Signals.sine(220.0, 1.0, fs, amplitude = 0.2)) { m ->
            assertTrue(m.voiced)
            assertEquals("A3", NoteNames.en(m.note))
            assertEquals(0.0, m.cents, 1.0)
        }
        run(Signals.sine(226.0, 1.0, fs, amplitude = 0.2)) { m ->
            assertEquals("A3", NoteNames.en(m.note))
            assertEquals(46.0, m.cents, 1.5)
        }
    }

    @Test
    fun `white noise is not voiced and metrics are off`() {
        var frames = 0
        run(Signals.whiteNoise(1.5, fs, rms = 0.1, seed = 9)) { m ->
            frames++
            assertTrue(m.confidence < 0.5, "confidence ${m.confidence}")
            assertTrue(!m.voiced)
            assertTrue(m.gate == Gate.LOW_CONFIDENCE || m.gate == Gate.NO_VOICE)
            assertEquals(0.0, m.score)
            assertEquals(0, m.overtoneCount)
        }
        assertTrue(frames > 50)
    }

    @Test
    fun `vibrato 5_5 Hz ±70 cents`() {
        val voice = Signals.harmonicVoice(VoiceSpec(PitchContour.Vibrato(220.0, 5.5, 70.0)), 4.0, fs)
        var checked = 0
        run(voice) { m ->
            if (m.timeSec > 4.0) {
                checked++
                assertEquals(5.5, m.vibrato.rateHz, 0.3)
                assertEquals(70.0, m.vibrato.extentCents, 10.0)
                assertEquals(VibratoKind.VIBRATO, m.vibrato.kind)
                assertTrue(m.pitchSd < 10.0, "pitchSd ${m.pitchSd}")
            }
        }
        assertTrue(checked > 50)
    }

    @Test
    fun `glissando +50 cents per second`() {
        val voice = Signals.harmonicVoice(VoiceSpec(PitchContour.Glissando(220.0, 50.0)), 4.0, fs)
        run(voice) { m -> if (m.timeSec > 3.5) assertEquals(50.0, m.driftCentsPerSec, 5.0) }
    }

    @Test
    fun `a hump in the ring band earns the ring regardless of loudness`() {
        val band = VoiceType.UNSET.band
        val plainSpec = VoiceSpec(PitchContour.Constant(220.0), tiltDbPerOctave = -6.0, amplitude = 0.2, normalise = false)
        val plain = Signals.harmonicVoice(plainSpec, 2.0, fs)
        val boosted = Signals.harmonicVoice(plainSpec.copy(bandGains = listOf(BandGain(band.loHz, band.hiHz, 12.0))), 2.0, fs)

        var plainRing = 0.0
        var plainHump = 0.0
        run(plain) { m -> if (m.timeSec > 2.5) { plainRing = m.ring; plainHump = m.humpDb } }
        var loudRing = 0.0
        var loudShare = 0.0
        var loudHump = 0.0
        var lastScore = 0.0
        run(boosted) { m -> if (m.timeSec > 2.5) { loudRing = m.ring; loudShare = m.ringSharePct; loudHump = m.humpDb; lastScore = m.score } }
        var quietRing = 0.0
        run(Signals.gain(boosted, -20.0)) { m -> if (m.timeSec > 2.5) quietRing = m.ring }

        assertEquals(12.0, loudHump - plainHump, 1.5)
        assertTrue(loudRing > plainRing + 0.3, "boosted ring $loudRing vs plain $plainRing")
        assertTrue(loudShare > 12.0, "share $loudShare")
        assertEquals(1.0, loudRing, 0.05) // +12 dB hump with an eighth of the energy → full ring
        assertTrue(lastScore > 0.9, "score $lastScore")
        assertEquals(loudRing, quietRing, 0.1) // 20 dB quieter: the same cupola
    }

    @Test
    fun `full pipeline benchmark`() {
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = fs))
        // 1 s of room noise first so the floor initialises and the voice path (YIN etc.) actually runs
        val voice = Signals.mix(
            Signals.whiteNoise(11.0, fs, rms = room),
            Signals.concat(Signals.silence(1.0, fs), Signals.harmonicVoice(VoiceSpec(PitchContour.Vibrato(220.0, 5.5, 50.0)), 10.0, fs)),
        )
        val asFloat = FloatArray(voice.size) { voice[it].toFloat() }
        analyzer.push(asFloat) { _, _ -> } // warm-up
        analyzer.resetAll()
        var frames = 0
        var voiced = 0
        val t0 = System.nanoTime()
        analyzer.push(asFloat) { m, _ -> frames++; if (m.voiced) voiced++ }
        val perFrameMs = (System.nanoTime() - t0) / 1e6 / frames
        println("Analyzer: %.3f ms per frame (%d frames, %d voiced)".format(perFrameMs, frames, voiced))
        assertTrue(frames > 1000)
        assertTrue(voiced > 900, "voiced $voiced")
        assertTrue(perFrameMs < 5.0, "too slow: $perFrameMs ms per frame (target < 2 ms)")
    }
}
