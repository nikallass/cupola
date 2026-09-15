package ru.dvedev.me.cupola.dsp.calibration

import ru.dvedev.me.cupola.dsp.Analyzer
import ru.dvedev.me.cupola.dsp.AnalyzerConfig
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationSessionTest {
    private val fs = 48_000

    private fun run(input: DoubleArray): Pair<CalibrationSession, Analyzer> {
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = fs))
        val session = CalibrationSession(analyzer.hopSeconds, VoiceType.UNSET.band, now = { 12345L })
        analyzer.push(input) { m, _ ->
            if (session.phase != CalibrationPhase.DONE) {
                session.push(CalibrationSession.Input(m.splDbfs, m.ringRatioDb, m.splDbfs, m.confidence, m.voice))
            }
        }
        return session to analyzer
    }

    @Test
    fun `synthetic calibration gives expected baselines`() {
        val vowel = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0), amplitude = 0.2, normalise = false), 4.5, fs)
        val input = Signals.mix(
            Signals.whiteNoise(6.5, fs, rms = 0.0005), // −66 dBFS room
            Signals.concat(Signals.silence(2.0, fs), vowel),
        )
        val (session, _) = run(input)
        assertEquals(CalibrationPhase.DONE, session.phase)
        val result = session.result()
        assertFalse(result.mustRepeat)
        assertTrue(result.warnings.isEmpty(), "warnings ${result.warnings}")
        val c = assertNotNull(result.calibration)
        assertEquals(-66.0, c.noiseFloorDbfs, 2.0)
        assertEquals(Signals.rmsDb(vowel), c.splDbfs, 1.0)
        assertTrue(c.voicedShare > 0.9, "voiced share ${c.voicedShare}")
        assertTrue(c.ringRatioDb < -10.0 && c.ringRatioDb > -60.0, "ring baseline ${c.ringRatioDb}")
        assertEquals(12345L, c.createdAtEpochMs)
        assertEquals(VoiceType.UNSET.band, c.band)
    }

    @Test
    fun `noisy room is flagged but still calibrates`() {
        val vowel = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0), amplitude = 0.3, normalise = false), 4.5, fs)
        val input = Signals.mix(
            Signals.whiteNoise(6.5, fs, rms = 0.01), // −40 dBFS
            Signals.concat(Signals.silence(2.0, fs), vowel),
        )
        val result = run(input).first.result()
        assertTrue(CalibrationWarning.NOISY_ROOM in result.warnings)
        assertNotNull(result.calibration)
    }

    @Test
    fun `unstable voice must be repeated`() {
        // only 1 s of voice inside the 4 s vowel phase
        val input = Signals.mix(
            Signals.whiteNoise(6.5, fs, rms = 0.0005),
            Signals.concat(
                Signals.silence(2.0, fs),
                Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0)), 1.0, fs),
                Signals.silence(3.5, fs),
            ),
        )
        val result = run(input).first.result()
        assertTrue(result.mustRepeat)
        assertNull(result.calibration)
        assertTrue(CalibrationWarning.UNSTABLE_VOICE in result.warnings)
    }

    @Test
    fun `progress and phases advance`() {
        val s = CalibrationSession(0.01, VoiceType.TENOR.band, silenceSeconds = 1.0, vowelSeconds = 1.0)
        assertEquals(CalibrationPhase.SILENCE, s.phase)
        repeat(50) { s.push(CalibrationSession.Input(-60.0, -20.0, -60.0, 0.0, false)) }
        assertEquals(0.5, s.progress, 1e-9)
        assertEquals(0.5, s.secondsLeft, 1e-9)
        repeat(50) { s.push(CalibrationSession.Input(-60.0, -20.0, -60.0, 0.0, false)) }
        assertEquals(CalibrationPhase.VOWEL, s.phase)
        repeat(100) { s.push(CalibrationSession.Input(-20.0, -12.0, -20.0, 0.9, true)) }
        assertEquals(CalibrationPhase.DONE, s.phase)
        val c = assertNotNull(s.result().calibration)
        assertEquals(-12.0, c.ringRatioDb, 1e-9)
        assertEquals(-20.0, c.splDbfs, 1e-9)
    }
}
