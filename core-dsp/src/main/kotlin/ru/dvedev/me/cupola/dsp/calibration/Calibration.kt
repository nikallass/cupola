package ru.dvedev.me.cupola.dsp.calibration

import ru.dvedev.me.cupola.dsp.metrics.RingBand

/**
 * Personal baseline (SPEC §6.6, §15.5). Every ring number the app shows is relative to
 * [ringRatioDb] at [splDbfs]; a different device, distance or room needs a new one.
 * Persistence lives in `:app`, keyed by voice type / custom band.
 */
data class Calibration(
    val band: RingBand,
    /** RMS floor measured during the silence phase, dBFS. */
    val noiseFloorDbfs: Double,
    /** `RingRatio_baseline`: median RingRatio of the /a/ phase. */
    val ringRatioDb: Double,
    /** `SPL_baseline`: median SPL (RMS dBFS) of the /a/ phase. */
    val splDbfs: Double,
    /** Share of /a/ frames that were voiced with confidence ≥ 0.7 (quality indicator). */
    val voicedShare: Double,
    val createdAtEpochMs: Long,
)
