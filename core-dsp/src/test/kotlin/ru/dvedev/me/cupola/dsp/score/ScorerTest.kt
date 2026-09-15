package ru.dvedev.me.cupola.dsp.score

import ru.dvedev.me.cupola.dsp.metrics.VibratoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScorerTest {
    private val hop = 0.01
    private fun good(share: Double = 25.0, hump: Double = 8.0) = ScoreInput(
        voice = true, confidence = 0.9, cents = 2.0, pitchSd = 0.0, vibratoKind = VibratoKind.STRAIGHT,
        ringSharePct = share, humpDb = hump,
    )

    /** Runs the same input for [seconds] so the smoothers settle; returns the last output. */
    private fun settle(scorer: Scorer, input: ScoreInput, seconds: Double = 3.0): ScoreOutput {
        var out: ScoreOutput? = null
        repeat((seconds / hop).toInt()) { out = scorer.frame(input) }
        return out!!
    }

    @Test
    fun `open gate with full ring, pitch and steadiness scores 1`() {
        val out = settle(Scorer(hop), good(), seconds = 3.5)
        assertEquals(Gate.OPEN, out.gate)
        assertEquals(1.0, out.ringRaw, 1e-9)
        assertEquals(1.0, out.score, 0.02)
        assertTrue(out.inStreak)
    }

    @Test
    fun `each gate branch`() {
        val s = Scorer(hop)
        assertEquals(Gate.NO_VOICE, s.frame(good().copy(voice = false)).gate)
        assertEquals(Gate.LOW_CONFIDENCE, s.frame(good().copy(confidence = 0.5)).gate)
        assertEquals(Gate.SOVT, s.frame(good().copy(sovt = true)).gate)
        // an unstable pitch no longer blocks the ring (2026‑09‑15): it only lowers `steady`
        assertEquals(Gate.OPEN, s.frame(good().copy(pitchSd = 25.0)).gate)
        assertEquals(Gate.OPEN, s.frame(good()).gate)
    }

    @Test
    fun `gated frames give ring 0`() {
        for (input in listOf(good().copy(confidence = 0.2), good().copy(voice = false), good().copy(sovt = true))) {
            assertEquals(0.0, Scorer(hop).frame(input).ringRaw)
        }
    }

    @Test
    fun `ring needs both a share and a hump and is monotonic in each`() {
        val s = Scorer(hop)
        assertEquals(0.0, s.ringRaw(30.0, -6.0)) // energy in the band but no hump: nothing
        assertEquals(0.0, s.ringRaw(2.0, 10.0)) // a hump but a negligible share: nothing
        assertEquals(1.0, s.ringRaw(12.0, 6.0), 1e-9)
        assertEquals(0.5, s.ringRaw(7.0, 0.0), 1e-9) // half share credit × half hump credit → √0.25
        var prev = -1.0
        for (share in listOf(0.0, 5.0, 10.0, 15.0, 20.0, 40.0)) {
            val r = s.ringRaw(share, 6.0)
            assertTrue(r >= prev, "share $share: $r < $prev")
            prev = r
        }
        prev = -1.0
        for (hump in listOf(-10.0, -3.0, 0.0, 3.0, 6.0, 12.0)) {
            val r = s.ringRaw(12.0, hump)
            assertTrue(r >= prev, "hump $hump: $r < $prev")
            prev = r
        }
        var prevScore = -1.0
        for (hump in listOf(-6.0, -3.0, 0.0, 3.0, 6.0)) {
            val out = settle(Scorer(hop), good(hump = hump))
            assertTrue(out.score >= prevScore - 1e-9)
            prevScore = out.score
        }
    }

    @Test
    fun `pitch and steady ramps`() {
        val s = Scorer(hop)
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
        val s = Scorer(hop)
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
