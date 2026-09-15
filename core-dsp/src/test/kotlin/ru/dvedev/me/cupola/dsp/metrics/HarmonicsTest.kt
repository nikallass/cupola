package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.TestPipeline
import ru.dvedev.me.cupola.dsp.util.RollingMedian
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonicsTest {
    private val fs = 48_000

    /** 1 s of noise (floor initialisation) followed by the signal; returns per-frame overtone counts after t0. */
    private fun overtoneCounts(signal: DoubleArray, noiseRms: Double, seed: Int = 3): Pair<List<Int>, HarmonicSet> {
        val p = TestPipeline(fs)
        val noise = Signals.whiteNoise(1.0 + signal.size.toDouble() / fs, fs, rms = noiseRms, seed = seed)
        val input = Signals.mix(noise, Signals.concat(Signals.silence(1.0, fs), signal))
        val floor = NoiseFloor(p.spectrum.bins, p.hopSeconds)
        val tracker = HarmonicTracker()
        val median = RollingMedian(30)
        val counts = mutableListOf<Int>()
        var last: HarmonicSet = tracker.result
        p.run(input) { s ->
            val voice = floor.update(s.spectrum.db, s.rmsDb)
            if (s.frame.endSeconds > 1.3 && voice && s.pitch.confidence >= 0.7) {
                last = tracker.track(s.pitch.f0Hz, s.spectrum, floor)
                median.push(last.overtoneCount().toDouble())
                counts += median.median().toInt()
            }
        }
        return counts to last
    }

    @Test
    fun `sawtooth harmonics sit on k·f0 and count grows as noise falls`() {
        // −9 dB/octave source: the upper harmonics sink into −30 dBFS noise while the RMS
        // voice gate (noise + 10 dB) still passes
        val saw = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(200.0), tiltDbPerOctave = -9.0, maxHarmonics = 40, amplitude = 0.3), 1.5, fs)
        val (loud, setLoud) = overtoneCounts(saw, noiseRms = 0.03)
        val (quiet, setQuiet) = overtoneCounts(saw, noiseRms = 0.0005)
        assertTrue(loud.isNotEmpty(), "no voiced frames with loud noise")
        assertTrue(quiet.isNotEmpty(), "no voiced frames with quiet noise")
        for (i in 0 until setQuiet.count) {
            assertEquals(setQuiet.k[i] * 200.0, setQuiet.hz[i], 200.0 * 0.03, "harmonic ${setQuiet.k[i]}")
        }
        val loudMedian = loud.sorted()[loud.size / 2]
        val quietMedian = quiet.sorted()[quiet.size / 2]
        println("overtones: loud noise $loudMedian, quiet noise $quietMedian")
        assertTrue(quietMedian >= loudMedian + 5, "count should grow as noise falls: $loudMedian → $quietMedian")
        assertTrue(quietMedian >= 30, "quiet source should expose ≥ 30 overtones, got $quietMedian")
        assertTrue(setLoud.count >= 2)
    }

    @Test
    fun `pure sine has no overtones`() {
        val sine = Signals.sine(220.0, 1.0, fs, amplitude = 0.3)
        val (counts, _) = overtoneCounts(sine, noiseRms = 0.001)
        assertTrue(counts.isNotEmpty())
        assertTrue(counts.all { it == 0 }, "sine overtone counts: ${counts.distinct()}")
    }

    @Test
    fun `noise floor separates voice from silence`() {
        val p = TestPipeline(fs)
        val floor = NoiseFloor(p.spectrum.bins, p.hopSeconds)
        val input = Signals.mix(
            Signals.whiteNoise(3.0, fs, rms = 0.001),
            Signals.concat(Signals.silence(1.5, fs), Signals.sine(220.0, 1.5, fs, amplitude = 0.1)),
        )
        var voicedBefore = 0
        var voicedAfter = 0
        var framesAfter = 0
        p.run(input) { s ->
            val v = floor.update(s.spectrum.db, s.rmsDb)
            if (s.frame.endSeconds < 1.5) { if (v) voicedBefore++ } else { framesAfter++; if (v) voicedAfter++ }
        }
        assertEquals(0, voicedBefore)
        assertTrue(voicedAfter > framesAfter * 0.9, "voiced $voicedAfter of $framesAfter")
        assertTrue(floor.initialized)
        assertTrue(abs(floor.rmsFloorDb - (-60.0)) < 3.0, "rms floor ${floor.rmsFloorDb}")
    }
}
