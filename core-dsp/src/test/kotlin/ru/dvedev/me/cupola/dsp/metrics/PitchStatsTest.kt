package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.TestPipeline
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchStatsTest {
    private val fs = 48_000

    private class Trace(val t: Double, val sd: Double, val drift: Double, val median: Double, val residual: Double)

    private fun trace(contour: PitchContour, seconds: Double): List<Trace> {
        val p = TestPipeline(fs)
        val stats = PitchStats(p.hopSeconds)
        val out = mutableListOf<Trace>()
        p.run(Signals.harmonicVoice(VoiceSpec(contour), seconds, fs)) { s ->
            stats.push(if (s.pitch.confidence >= 0.7) s.pitch.f0Hz else 0.0)
            out += Trace(s.frame.endSeconds, stats.pitchSd, stats.drift, stats.medianCents, stats.residual)
        }
        return out
    }

    @Test
    fun `vibrato 5_5 Hz ±70 cents keeps pitchSD below 10`() {
        val tr = trace(PitchContour.Vibrato(220.0, 5.5, 70.0), 3.0).filter { it.t > 1.0 }
        val sds = tr.map { it.sd }.filter { !it.isNaN() }
        assertTrue(sds.isNotEmpty())
        val maxSd = sds.max()
        println("vibrato pitchSD max = %.2f, median line ripple = %.2f".format(maxSd, tr.map { it.median }.filter { !it.isNaN() }.let { m -> m.max() - m.min() }))
        assertTrue(maxSd < 10.0, "pitchSD $maxSd")
        // the residual still carries the vibrato
        val residualPeak = tr.map { kotlin.math.abs(it.residual) }.filter { !it.isNaN() }.max()
        assertTrue(residualPeak > 50.0, "residual peak $residualPeak")
    }

    @Test
    fun `steady tone has near-zero SD and drift`() {
        val tr = trace(PitchContour.Constant(300.0), 3.0).filter { it.t > 2.5 }
        assertTrue(tr.all { it.sd < 2.0 }, "sd ${tr.map { it.sd }.max()}")
        assertTrue(tr.all { kotlin.math.abs(it.drift) < 2.0 }, "drift ${tr.map { it.drift }}")
    }

    @Test
    fun `glissando +50 cents per second gives drift 50 ± 5`() {
        val tr = trace(PitchContour.Glissando(220.0, 50.0), 4.0).filter { it.t > 2.5 }
        val drifts = tr.map { it.drift }.filter { !it.isNaN() }
        assertTrue(drifts.isNotEmpty())
        for (d in drifts) assertEquals(50.0, d, 5.0)
    }

    @Test
    fun `unvoiced gap resets the median line`() {
        val p = TestPipeline(fs)
        val stats = PitchStats(p.hopSeconds)
        val signal = Signals.concat(
            Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0)), 1.0, fs),
            Signals.silence(0.5, fs),
            Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(330.0)), 1.0, fs),
        )
        var sawNaN = false
        var lastMedian = Double.NaN
        p.run(signal) { s ->
            stats.push(if (s.pitch.confidence >= 0.7) s.pitch.f0Hz else 0.0)
            if (s.frame.endSeconds in 1.2..1.45 && stats.medianCents.isNaN()) sawNaN = true
            lastMedian = stats.medianCents
        }
        assertTrue(sawNaN)
        assertEquals(PitchStats.absoluteCents(330.0), lastMedian, 3.0)
    }
}
