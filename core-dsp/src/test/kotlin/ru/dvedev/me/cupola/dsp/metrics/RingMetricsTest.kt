package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.TestPipeline
import ru.dvedev.me.cupola.testdata.BandGain
import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingMetricsTest {
    private val fs = 48_000
    private val band = VoiceType.UNSET.band // 2400–3200

    private fun measure(signal: DoubleArray, b: RingBand = band): RingMeasure {
        val p = TestPipeline(fs)
        val all = mutableListOf<RingMeasure>()
        p.run(signal) { s -> all += RingMetrics.measure(s.spectrum, b, RingMetrics.splDbfs(s.frame.raw)) }
        // median over frames
        fun med(f: (RingMeasure) -> Double) = all.map(f).sorted()[all.size / 2]
        return RingMeasure(med { it.ringRatioDb }, med { it.ringSharePct }, med { it.peakSprDb }, med { it.splDbfs })
    }

    private val voice = VoiceSpec(PitchContour.Constant(220.0), tiltDbPerOctave = -9.0, amplitude = 0.2, normalise = false)

    @Test
    fun `plus 10 dB in the ring band raises RingRatio by 10 dB`() {
        val plain = measure(Signals.harmonicVoice(voice, 1.0, fs))
        val boosted = measure(Signals.harmonicVoice(voice.copy(bandGains = listOf(BandGain(band.loHz, band.hiHz, 10.0))), 1.0, fs))
        assertEquals(10.0, boosted.ringRatioDb - plain.ringRatioDb, 1.0)
        assertTrue(boosted.ringSharePct > plain.ringSharePct)
        assertTrue(boosted.peakSprDb > plain.peakSprDb)
    }

    @Test
    fun `loudness-consistent voices give equal RingRatio_norm within 1 dB`() {
        // Bloothooft & Plomp: +10 dB SPL brings ≈ +7 dB extra in the formant region → k = 0.7 cancels it
        val soft = measure(Signals.harmonicVoice(voice, 1.0, fs))
        val loud = measure(
            Signals.gain(Signals.harmonicVoice(voice.copy(bandGains = listOf(BandGain(band.loHz, band.hiHz, 7.0))), 1.0, fs), 10.0),
        )
        assertEquals(10.0, loud.splDbfs - soft.splDbfs, 0.5)
        val baseline = soft.splDbfs
        val normSoft = RingMetrics.normalise(soft.ringRatioDb, soft.splDbfs, baseline)
        val normLoud = RingMetrics.normalise(loud.ringRatioDb, loud.splDbfs, baseline)
        assertEquals(normSoft, normLoud, 1.0)
    }

    @Test
    fun `pure gain leaves RingRatio unchanged and lowers RingRatio_norm by k·ΔSPL`() {
        val soft = measure(Signals.harmonicVoice(voice, 1.0, fs))
        val loud = measure(Signals.gain(Signals.harmonicVoice(voice, 1.0, fs), 10.0))
        assertEquals(soft.ringRatioDb, loud.ringRatioDb, 0.2)
        val normSoft = RingMetrics.normalise(soft.ringRatioDb, soft.splDbfs, soft.splDbfs)
        val normLoud = RingMetrics.normalise(loud.ringRatioDb, loud.splDbfs, soft.splDbfs)
        assertEquals(-7.0, normLoud - normSoft, 0.5)
    }

    @Test
    fun `band choice changes the result as expected`() {
        val boosted = Signals.harmonicVoice(voice.copy(bandGains = listOf(BandGain(2700.0, 3500.0, 12.0))), 1.0, fs)
        val soprano = measure(boosted, VoiceType.SOPRANO.band) // 2700–3500 — fully inside the boost
        val bass = measure(boosted, VoiceType.BASS.band) // 2000–2800 — mostly outside
        assertTrue(soprano.ringRatioDb > bass.ringRatioDb + 6.0, "soprano ${soprano.ringRatioDb} vs bass ${bass.ringRatioDb}")
    }

    @Test
    fun `voice type bands follow SPEC 5_3`() {
        assertEquals(RingBand(2000.0, 2800.0), VoiceType.BASS.band)
        assertEquals(RingBand(2050.0, 2850.0), VoiceType.BARITONE.band)
        assertEquals(RingBand(2300.0, 3100.0), VoiceType.TENOR.band)
        assertEquals(RingBand(2500.0, 3300.0), VoiceType.ALTO.band)
        assertEquals(RingBand(2700.0, 3500.0), VoiceType.SOPRANO.band)
        assertEquals(RingBand(2400.0, 3200.0), VoiceType.UNSET.band)
    }

    @Test
    fun `spl of a sine equals its rms in dBFS`() {
        val raw = Signals.sine(440.0, 2048.0 / fs, fs, amplitude = 1.0)
        assertEquals(-3.01, RingMetrics.splDbfs(raw), 0.05)
    }
}
