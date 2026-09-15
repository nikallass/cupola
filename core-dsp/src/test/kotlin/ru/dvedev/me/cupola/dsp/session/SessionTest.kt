package ru.dvedev.me.cupola.dsp.session

import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.metrics.Vibrato
import ru.dvedev.me.cupola.dsp.score.Gate
import ru.dvedev.me.cupola.notation.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTest {
    private fun frame(t: Double, score: Double, ring: Double, voice: Boolean = true, streak: Double = 0.0) = FrameMetrics(
        timeSec = t, voice = voice, f0Hz = if (voice) 220.0 else 0.0, confidence = if (voice) 0.9 else 0.0,
        note = Note(57), cents = if (voice) 3.0 else Double.NaN, splDbfs = -20.0, noiseFloorDbfs = -60.0,
        ringRatioDb = -10.0, ringRatioNorm = -8.0, ringSharePct = 12.0, peakSprDb = -20.0, overtoneCount = 8,
        harmonics = emptyList(), pitchSd = 4.0, driftCentsPerSec = 0.0, vibrato = Vibrato.NONE,
        gate = if (voice) Gate.OPEN else Gate.NO_VOICE, ring = ring, pitch = 0.9, steady = 0.8, score = score,
        streakSeconds = streak, calibrated = true,
    )

    @Test
    fun `points follow the interval and portion rules`() {
        val p = PointsCounter()
        assertEquals(0.6, p.intervalFor(0.0), 1e-9)
        assertEquals(0.12, p.intervalFor(1.0), 1e-9)
        assertEquals(1, p.portionFor(0.0))
        assertEquals(2, p.portionFor(0.5))
        assertEquals(3, p.portionFor(1.0))

        // 10 s at score 0.8, ring 1.0: first award after 0.12 s, then every 0.12 s → 3 points each
        var t = 0.0
        var awards = 0
        while (t < 10.0) {
            if (p.update(t, 0.8, 1.0) > 0) awards++
            t += 0.01
        }
        // 10 s / 0.12 s ≈ 83; the 10 ms tick quantises each interval up to 0.13 s
        assertTrue(awards in 76..84, "awards $awards")
        assertEquals(awards * 3, p.total)

        // below threshold nothing is awarded and the timer restarts
        p.reset()
        repeat(100) { p.update(it * 0.01, 0.3, 1.0) }
        assertEquals(0, p.total)
        assertEquals(0, p.update(1.0, 0.8, 0.0)) // crossing: no immediate award
        assertEquals(0, p.update(1.5, 0.8, 0.0))
        assertEquals(1, p.update(1.6, 0.8, 0.0))
    }

    @Test
    fun `summary over a known frame sequence`() {
        val acc = SessionAccumulator(hopSeconds = 0.01, decimation = 10, startedAtEpochMs = 1000)
        var t = 0.0
        // 2 s silence, 4 s at score 0.9 (ring 0.5), 4 s at score 0.3 (ring 0)
        repeat(200) { acc.add(frame(t, 0.0, 0.0, voice = false)); t += 0.01 }
        repeat(400) { acc.add(frame(t, 0.9, 0.5, streak = if (it > 300) 3.0 + (it - 300) * 0.01 else 0.0)); t += 0.01 }
        repeat(400) { acc.add(frame(t, 0.3, 0.0)); t += 0.01 }
        val s = acc.summary()
        assertEquals(10.0, s.durationSec, 1e-6)
        assertEquals(8.0, s.voicedSec, 1e-6)
        assertEquals(0.6, s.meanScore, 1e-9)
        assertEquals(0.9, s.bestScore, 1e-9)
        assertEquals(0.5, s.shareAbove07, 1e-9)
        assertEquals(0.25, s.meanRing, 1e-9)
        assertEquals(3.99, s.bestStreakSec, 1e-6)
        assertEquals(1000, s.frames)
        assertTrue(s.points > 0)
        // points only during the 4 s above 0.5: interval 0.6 − 0.24 = 0.36 s → 10–11 awards × 2
        assertTrue(s.points in 20..22, "points ${s.points}")
        assertEquals(100, acc.frameLog.size)
        val rec = acc.recording()
        assertEquals(1000, rec.startedAtEpochMs)
        assertEquals(s, rec.summary)
        assertEquals(Gate.NO_VOICE, rec.frames.first().gate)
    }

    @Test
    fun `empty session`() {
        assertEquals(SessionSummary.EMPTY, SessionAccumulator(0.01).summary())
    }
}
