package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum

/** Harmonics found in one frame; arrays are reused by the tracker, copy before keeping. */
class HarmonicSet(maxK: Int) {
    var count: Int = 0
        internal set
    val k: IntArray = IntArray(maxK)
    val hz: DoubleArray = DoubleArray(maxK)
    val levelDb: DoubleArray = DoubleArray(maxK)
    val audible: BooleanArray = BooleanArray(maxK)

    /** Level of the fundamental (`H_1`), or NaN if none. */
    val h1Db: Double get() = if (count > 0 && k[0] == 1) levelDb[0] else Double.NaN

    /** Audible harmonics with `k ≥ 2` — "обертоны" in the Russian sense (SPEC §5.2). */
    fun overtoneCount(includeFundamental: Boolean = false): Int {
        var n = 0
        for (i in 0 until count) if (audible[i] && (includeFundamental || k[i] >= 2)) n++
        return n
    }

    /** Independent copy for the UI thread. */
    fun snapshot(): List<Harmonic> = List(count) { Harmonic(k[it], hz[it], levelDb[it], audible[it]) }
}

data class Harmonic(val k: Int, val hz: Double, val levelDb: Double, val audible: Boolean)

/**
 * Finds the spectral peak near each `k·f0` (±[toleranceRatio]) up to [maxHz] and decides
 * whether it is audible: `H_k ≥ noiseFloor(k·f0) + 12 dB` and `H_k ≥ H_1 − 50 dB` (SPEC §5.2).
 */
class HarmonicTracker(
    val maxHz: Double = 8000.0,
    val maxK: Int = 40,
    val toleranceRatio: Double = 0.03,
    val aboveFloorDb: Double = 12.0,
    val belowH1Db: Double = 50.0,
) {
    val result = HarmonicSet(maxK)

    fun track(f0Hz: Double, spectrum: PowerSpectrum, noise: NoiseFloor): HarmonicSet {
        result.count = 0
        if (f0Hz <= 0.0) return result
        var h1 = Double.NaN
        var k = 1
        while (k <= maxK) {
            val target = k * f0Hz
            if (target > maxHz || target > spectrum.nyquistHz) break
            // ±3 % of k·f0, but never past 40 % of the harmonic spacing (above k ≈ 16 the
            // relative window would otherwise swallow the neighbouring harmonic)
            val halfWidth = minOf(target * toleranceRatio, NEIGHBOUR_GUARD * f0Hz)
            val lo = target - halfWidth
            val hi = target + halfWidth
            val bin = spectrum.peakBin(lo, hi)
            val level = spectrum.db[bin]
            if (k == 1) h1 = level
            val floor = noise.floorAt(bin)
            val i = result.count
            result.k[i] = k
            result.hz[i] = spectrum.refinePeakHz(bin)
            result.levelDb[i] = level
            result.audible[i] = level >= floor + aboveFloorDb && level >= h1 - belowH1Db
            result.count = i + 1
            k++
        }
        return result
    }

    companion object {
        const val NEIGHBOUR_GUARD = 0.4
    }
}
