package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Frequency band of the singer's formant used for the ring metrics. */
data class RingBand(val loHz: Double, val hiHz: Double) {
    init {
        require(hiHz - loHz >= MIN_WIDTH_HZ) { "ring band must be at least $MIN_WIDTH_HZ Hz wide" }
        require(loHz > 0) { "band must be positive" }
    }

    val centerHz: Double get() = (loHz + hiHz) / 2

    companion object {
        const val MIN_WIDTH_HZ = 200.0
        const val CUSTOM_STEP_HZ = 50.0
    }
}

/** Voice types with their ring bands (SPEC §5.3, centre ±400 Hz after Müller et al. 2022). */
enum class VoiceType(val centerHz: Int) {
    BASS(2400),
    BARITONE(2450),
    TENOR(2700),
    ALTO(2900), // контральто / меццо
    SOPRANO(3100),
    UNSET(2800);

    val band: RingBand = RingBand(centerHz - 400.0, centerHz + 400.0)
}

/** One frame of ring measurements (SPEC §5.3), all in dB except [ringSharePct]. */
data class RingMeasure(
    val ringRatioDb: Double,
    val ringSharePct: Double,
    val peakSprDb: Double,
    val splDbfs: Double,
)

object RingMetrics {
    /** `k` of the loudness normalisation, Bloothooft & Plomp (SPEC §5.3). */
    const val DEFAULT_K = 0.7
    const val REF_LO_HZ = 300.0
    const val REF_HI_HZ = 2000.0
    const val TOTAL_LO_HZ = 80.0
    const val TOTAL_HI_HZ = 8000.0
    const val FLOOR = 1e-14

    fun measure(spectrum: PowerSpectrum, band: RingBand, splDbfs: Double): RingMeasure {
        val ring = spectrum.energy(band.loHz, band.hiHz)
        val ref = spectrum.energy(REF_LO_HZ, REF_HI_HZ)
        val total = spectrum.energy(TOTAL_LO_HZ, TOTAL_HI_HZ)
        val ringRatio = 10.0 * log10(max(ring, FLOOR) / max(ref, FLOOR))
        val share = if (total > FLOOR) 100.0 * ring / total else 0.0
        val spr = spectrum.peakDb(2000.0, 4000.0) - spectrum.peakDb(0.0, 2000.0)
        return RingMeasure(ringRatio, share, spr, splDbfs)
    }

    /** `RingRatio_norm = RingRatio − k·(SPL − SPL_baseline)`. */
    fun normalise(ringRatioDb: Double, splDbfs: Double, splBaselineDbfs: Double, k: Double = DEFAULT_K): Double =
        ringRatioDb - k * (splDbfs - splBaselineDbfs)

    /** SPL proxy: RMS of the raw frame in dBFS. */
    fun splDbfs(raw: DoubleArray): Double {
        var acc = 0.0
        for (v in raw) acc += v * v
        val rms = sqrt(acc / raw.size)
        return 20.0 * log10(max(rms, 1e-9))
    }
}
