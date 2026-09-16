package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
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

/**
 * One frame of ring measurements (SPEC §5.3 + owner decision 2026‑09‑15: no personal
 * calibration — the cupola is judged by its *share* of the voice's energy and by the
 * *hump* it makes above the neighbouring spectrum, both independent of loudness).
 * All in dB except [ringSharePct].
 */
data class RingMeasure(
    /** `E[band] / E[300–2000 Hz]`, dB — kept for the advanced readout. */
    val ringRatioDb: Double,
    /** Share of the (room-noise-subtracted) energy 80–8000 Hz that sits in the band, %. */
    val ringSharePct: Double,
    /** Singing power ratio: peak 2–4 kHz minus peak 0–2 kHz, dB. */
    val peakSprDb: Double,
    val splDbfs: Double,
    /** Band peak minus the mean of the flank peaks just below and above it, dB: > 0 is a hump. */
    val humpDb: Double,
    /** Voice level: energy 80–8000 Hz above the noise profile, dB (same scale as the spectrum). */
    val voiceDb: Double = Double.NaN,
)

object RingMetrics {
    const val REF_LO_HZ = 300.0
    const val REF_HI_HZ = 2000.0
    const val TOTAL_LO_HZ = 80.0
    const val TOTAL_HI_HZ = 8000.0
    const val FLOOR = 1e-14

    /** Flanks used for the hump: this far from the band edges, this wide. */
    const val FLANK_GAP_HZ = 100.0
    const val FLANK_WIDTH_HZ = 600.0

    /** Noise is subtracted with this factor over the 10th-percentile profile (≈ +3 dB). */
    const val NOISE_MARGIN = 2.0

    /**
     * Measures the band in [spectrum]. With [noise] the adaptive noise profile is subtracted bin by
     * bin before energies and peaks are taken, so noise inside the band earns nothing and
     * noise outside it does not dilute the share.
     */
    fun measure(spectrum: PowerSpectrum, band: RingBand, splDbfs: Double, noise: NoiseFloor? = null): RingMeasure {
        val ring = energy(spectrum, band.loHz, band.hiHz, noise)
        val ref = energy(spectrum, REF_LO_HZ, REF_HI_HZ, noise)
        val total = energy(spectrum, TOTAL_LO_HZ, TOTAL_HI_HZ, noise)
        val ringRatio = 10.0 * log10(max(ring, FLOOR) / max(ref, FLOOR))
        val share = if (total > FLOOR) 100.0 * ring / total else 0.0
        val spr = peakDb(spectrum, 2000.0, 4000.0, noise) - peakDb(spectrum, 0.0, 2000.0, noise)
        val lower = peakDb(spectrum, band.loHz - FLANK_GAP_HZ - FLANK_WIDTH_HZ, band.loHz - FLANK_GAP_HZ, noise)
        val upper = peakDb(spectrum, band.hiHz + FLANK_GAP_HZ, band.hiHz + FLANK_GAP_HZ + FLANK_WIDTH_HZ, noise)
        val hump = peakDb(spectrum, band.loHz, band.hiHz, noise) - 0.5 * (lower + upper)
        return RingMeasure(ringRatio, share, spr, splDbfs, hump, 10.0 * log10(max(total, FLOOR)))
    }

    private fun noiseLinear(noise: NoiseFloor, bin: Int): Double = NOISE_MARGIN * 10.0.pow(noise.floorAt(bin) / 10.0)

    /** Sum of linear power above the noise profile over bins whose centre lies in `[lo, hi]`. */
    fun energy(spectrum: PowerSpectrum, loHz: Double, hiHz: Double, noise: NoiseFloor?): Double {
        if (noise == null) return spectrum.energy(loHz, hiHz)
        var e = 0.0
        for (k in spectrum.binCeil(loHz)..spectrum.binFloor(hiHz)) e += max(spectrum.power[k] - noiseLinear(noise, k), 0.0)
        return e
    }

    /** Highest bin level in `[lo, hi]` after noise subtraction, dB. */
    fun peakDb(spectrum: PowerSpectrum, loHz: Double, hiHz: Double, noise: NoiseFloor?): Double {
        if (noise == null) return spectrum.peakDb(loHz, hiHz)
        var best = FLOOR
        for (k in spectrum.binCeil(loHz)..spectrum.binFloor(hiHz)) {
            val p = spectrum.power[k] - noiseLinear(noise, k)
            if (p > best) best = p
        }
        return 10.0 * log10(best)
    }

    /** SPL proxy: RMS of the raw frame in dBFS. */
    fun splDbfs(raw: DoubleArray): Double {
        var acc = 0.0
        for (v in raw) acc += v * v
        val rms = sqrt(acc / raw.size)
        return 20.0 * log10(max(rms, 1e-9))
    }
}
