package ru.dvedev.me.cupola.dsp.score

import ru.dvedev.me.cupola.dsp.calibration.Calibration
import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScorerTest {
    private val hop = 0.01
    private val base = Calibration(VoiceType.UNSET.band, noiseFloorDbfs = -60.0, ringRatioDb = -10.0, splDbfs = -20.0, voicedShare = 1.0, createdAtEpochMs = 0)

    private fun good(ringNorm: Double = -4.0) = ScoreInput(
        voice = true, confidence = 0.9, cents = 2.0, pitchSd = 0.0, vibratoKind = VibratoKind.STRAIGHT,
        ringRatioNorm = ringNorm, splDbfs = -20.0,
    )

    /** Runs the same input for [seconds] so the smoothers settle; returns the last output. */
    private fun settle(scorer: Scorer, input: ScoreInput, seconds: Double = 3.0): ScoreOutput {
        var out: ScoreOutput? = null
        repeat((seconds / hop).toInt()) { out = scorer.frame(input) }
        return out!!
    }

    @Test
    fun `open gate with full ring, pitch and steadiness scores 1`() {
        val out = settle(Scorer(hop, base), good(ringNorm = -4.0), seconds = 3.5)
        assertEquals(Gate.OPEN, out.gate)
        assertEquals(1.0, out.ringRaw, 1e-9)
        assertEquals(1.0, out.score, 0.02)
        assertTrue(out.inStreak)
    }

    @Test
    fun `each gate branch`() {
        val s = Scorer(hop, base)
        assertEquals(Gate.NO_VOICE, s.frame(good().copy(voice = false)).gate)
        assertEquals(Gate.LOW_CONFIDENCE, s.frame(good().copy(confidence = 0.5)).gate)
        assertEquals(Gate.SOVT, s.frame(good().copy(sovt = true)).gate)
        assertEquals(Gate.PUSHED, s.frame(good().copy(splDbfs = -20.0 + 13.0)).gate)
        assertEquals(Gate.UNSTABLE_PITCH, s.frame(good().copy(pitchSd = 25.0)).gate)
        assertEquals(Gate.UNSTABLE_PITCH, s.frame(good().copy(pitchSd = Double.NaN)).gate)
        assertEquals(Gate.OPEN, s.frame(good()).gate)
        assertEquals(Gate.NOT_CALIBRATED, Scorer(hop, null).frame(good()).gate)
    }

    @Test
    fun `gated frames give ring 0`() {
        for (input in listOf(good().copy(pitchSd = 30.0), good().copy(confidence = 0.2), good().copy(voice = false))) {
            assertEquals(0.0, Scorer(hop, base).frame(input).ringRaw)
        }
    }

    @Test
    fun `score is monotonic in ring`() {
        var prev = -1.0
        for (ringNorm in listOf(-12.0, -10.0, -8.0, -7.0, -6.0, -5.0, -4.0, 0.0)) {
            val out = settle(Scorer(hop, base), good(ringNorm))
            assertTrue(out.score >= prev - 1e-9, "ringNorm $ringNorm: ${out.score} < $prev")
            prev = out.score
        }
        assertEquals(0.5, settle(Scorer(hop, base), good(-7.0)).ringRaw, 1e-9)
    }

    @Test
    fun `pushed by 13 dB zeroes the score`() {
        val s = Scorer(hop, base)
        settle(s, good())
        val out = settle(s, good().copy(splDbfs = -7.0), seconds = 1.0)
        assertEquals(Gate.PUSHED, out.gate)
        assertEquals(0.0, out.score)
        assertEquals(0.0, out.streakSeconds)
    }

    @Test
    fun `pitch and steady ramps`() {
        val s = Scorer(hop, base)
        assertEquals(1.0, s.frame(good().copy(cents = -5.0)).pitchRaw, 1e-9)
        assertEquals(0.6, s.frame(good().copy(cents = 15.0)).pitchRaw, 1e-9)
        assertEquals(0.0, s.frame(good().copy(cents = 31.0)).pitchRaw, 1e-9)
        assertEquals(0.5, s.frame(good().copy(pitchSd = 15.0)).steadyRaw, 1e-9)
        assertEquals(0.3, s.frame(good().copy(pitchSd = 15.0, vibratoKind = VibratoKind.WOBBLE)).steadyRaw, 1e-9)
        assertEquals(0.5, s.frame(good().copy(pitchSd = 15.0, vibratoKind = VibratoKind.VIBRATO)).steadyRaw, 1e-9)
    }

    @Test
    fun `attack is faster than release`() {
        val ar = AttackRelease(0.08, 0.4, hop)
        repeat(8) { ar.process(1.0) }
        val afterAttack = ar.value
        assertTrue(afterAttack > 0.6, "after 80 ms attack: $afterAttack")
        repeat(40) { ar.process(0.0) }
        assertTrue(ar.value in 0.2..0.45, "after 400 ms release: ${ar.value}")
    }

    @Test
    fun `streak needs 3 seconds above 0_7`() {
        val s = Scorer(hop, base)
        // the 80 ms attack keeps score < 0.7 for the first ~11 frames, so the streak starts late
        var out = settle(s, good(), seconds = 2.9)
        assertEquals(0.0, out.streakSeconds)
        out = settle(s, good(), seconds = 0.4)
        assertTrue(out.streakSeconds >= 3.0, "streak ${out.streakSeconds}")
        // one unvoiced frame does not break it (release 400 ms bridges consonants); half a second does
        out = s.frame(good().copy(voice = false))
        assertTrue(out.streakSeconds > 3.0)
        out = settle(s, good().copy(voice = false), seconds = 0.5)
        assertEquals(0.0, out.streakSeconds)
    }
}
