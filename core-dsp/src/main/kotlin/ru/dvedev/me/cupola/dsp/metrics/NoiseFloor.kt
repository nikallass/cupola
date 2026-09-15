package ru.dvedev.me.cupola.dsp.metrics

import kotlin.math.roundToInt

/**
 * Noise floor estimate (SPEC §4, §5.2):
 *
 * - **RMS floor** — minimum tracker with a slow rise ([riseDbPerSecond]), so a long sung
 *   phrase does not pull the floor up; `voice = rmsDb > rmsFloorDb + 10 dB`.
 * - **Per-bin profile** — 10th percentile of recent *unvoiced* frames (decimated), used
 *   to decide whether a harmonic is audible. During the first [initSeconds] every frame
 *   feeds the profile, matching the "1 s of silence at start" procedure.
 */
class NoiseFloor(
    val bins: Int,
    val hopSeconds: Double,
    val initSeconds: Double = 1.0,
    val riseDbPerSecond: Double = 0.5,
    val voiceMarginDb: Double = 10.0,
    private val historySlots: Int = 200,
    private val decimation: Int = 2,
    private val recomputeEveryFrames: Int = 50,
) {
    private val initFrames = (initSeconds / hopSeconds).roundToInt().coerceAtLeast(1)
    private val history = Array(bins) { DoubleArray(historySlots) { Double.NaN } }
    private val scratch = DoubleArray(historySlots)
    private var histHead = 0
    private var histSize = 0
    private var frames = 0L
    private var framesSincePush = 0
    private var framesSinceRecompute = 0

    /** Per-bin noise level in dB. */
    val profileDb: DoubleArray = DoubleArray(bins) { INITIAL_DB }

    var rmsFloorDb: Double = Double.NaN
        private set

    val initialized: Boolean get() = frames >= initFrames && histSize >= MIN_HISTORY

    fun isVoice(rmsDb: Double): Boolean = !rmsFloorDb.isNaN() && rmsDb > rmsFloorDb + voiceMarginDb

    fun floorAt(bin: Int): Double = profileDb[bin.coerceIn(0, bins - 1)]

    /** Feed one frame; returns whether it was classified as voice. */
    fun update(spectrumDb: DoubleArray, rmsDb: Double): Boolean {
        frames++
        // RMS: instant fall, slow rise
        rmsFloorDb = when {
            rmsFloorDb.isNaN() || rmsDb < rmsFloorDb -> rmsDb
            else -> minOf(rmsDb, rmsFloorDb + riseDbPerSecond * hopSeconds)
        }
        val voice = frames > initFrames && isVoice(rmsDb)
        if (!voice) {
            if (++framesSincePush >= decimation) {
                framesSincePush = 0
                pushHistory(spectrumDb)
            }
        }
        if (++framesSinceRecompute >= recomputeEveryFrames || (frames == initFrames.toLong())) {
            framesSinceRecompute = 0
            recompute()
        }
        return voice
    }

    fun reset() {
        for (h in history) h.fill(Double.NaN)
        histHead = 0
        histSize = 0
        frames = 0
        framesSincePush = 0
        framesSinceRecompute = 0
        profileDb.fill(INITIAL_DB)
        rmsFloorDb = Double.NaN
    }

    private fun pushHistory(spectrumDb: DoubleArray) {
        for (b in 0 until bins) history[b][histHead] = spectrumDb[b]
        histHead = (histHead + 1) % historySlots
        if (histSize < historySlots) histSize++
    }

    private fun recompute() {
        if (histSize < MIN_HISTORY) return
        val idx = ((histSize - 1) * PERCENTILE).roundToInt()
        for (b in 0 until bins) {
            val h = history[b]
            var n = 0
            for (i in 0 until histSize) {
                val v = h[i]
                if (!v.isNaN()) scratch[n++] = v
            }
            if (n == 0) continue
            java.util.Arrays.sort(scratch, 0, n)
            profileDb[b] = scratch[minOf(idx, n - 1)]
        }
    }

    companion object {
        const val INITIAL_DB = -100.0
        const val PERCENTILE = 0.10
        const val MIN_HISTORY = 10
    }
}
