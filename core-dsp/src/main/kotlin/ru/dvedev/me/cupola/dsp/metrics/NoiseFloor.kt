package ru.dvedev.me.cupola.dsp.metrics

import kotlin.math.roundToInt

/**
 * Noise floor estimate (SPEC §4, §5.2; owner decision 2026‑09‑16: no room-noise measurement,
 * the floor adapts on its own):
 *
 * - **RMS floor** — minimum tracker with a slow rise ([riseDbPerSecond]), so a long sung
 *   phrase does not pull the floor up; `voice = rmsDb > rmsFloorDb + 10 dB`.
 * - **Per-bin profile** — 10th percentile of *unvoiced* frames (the quiet pauses between
 *   phrases) over the last [windowSeconds] of quiet (3 minutes by default). The first [fastSlots] samples are taken every
 *   [fastDecimation] quiet frames so the floor settles within seconds of opening the app;
 *   after that one quiet frame in [slowDecimation] is kept, so [historySlots] samples span
 *   [windowSeconds] of quiet, and the ring then rolls. During the first [initSeconds] every
 *   frame counts as quiet.
 */
class NoiseFloor(
    val bins: Int,
    val hopSeconds: Double,
    val initSeconds: Double = 1.0,
    val riseDbPerSecond: Double = 0.5,
    val voiceMarginDb: Double = 10.0,
    private val historySlots: Int = 600,
    private val fastSlots: Int = 200,
    private val fastDecimation: Int = 2,
    /** How much quiet time the profile covers, seconds (settings, default 3 min). */
    windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
    private val recomputeEveryFrames: Int = 50,
) {
    /** How much quiet time the profile covers, seconds; takes effect for the samples that follow. */
    @Volatile var windowSeconds: Double = windowSeconds

    /** Quiet frames per stored sample once the fast fill is done, so [historySlots] samples span [windowSeconds]. */
    private val slowDecimation: Int
        get() = ((windowSeconds / hopSeconds - fastSlots * fastDecimation) / (historySlots - fastSlots)).roundToInt().coerceAtLeast(fastDecimation)

    private val initFrames = (initSeconds / hopSeconds).roundToInt().coerceAtLeast(1)
    // Float storage: 1025 bins × 600 samples ≈ 2.5 MB
    private val history = Array(bins) { FloatArray(historySlots) { Float.NaN } }
    private val scratch = FloatArray(historySlots)
    private var histHead = 0
    private var histSize = 0
    private var frames = 0L
    private var framesSincePush = 0
    private var framesSinceRecompute = 0
    private var pushedSinceRecompute = 0

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
            val decimation = if (histSize < fastSlots) fastDecimation else slowDecimation
            if (++framesSincePush >= decimation) {
                framesSincePush = 0
                pushHistory(spectrumDb)
                pushedSinceRecompute++
            }
        }
        if ((++framesSinceRecompute >= recomputeEveryFrames && pushedSinceRecompute > 0) || frames == initFrames.toLong()) {
            framesSinceRecompute = 0
            pushedSinceRecompute = 0
            recompute()
        }
        return voice
    }

    fun reset() {
        for (h in history) h.fill(Float.NaN)
        histHead = 0
        histSize = 0
        frames = 0
        framesSincePush = 0
        framesSinceRecompute = 0
        pushedSinceRecompute = 0
        profileDb.fill(INITIAL_DB)
        rmsFloorDb = Double.NaN
    }

    private fun pushHistory(spectrumDb: DoubleArray) {
        for (b in 0 until bins) history[b][histHead] = spectrumDb[b].toFloat()
        histHead = (histHead + 1) % historySlots
        if (histSize < historySlots) histSize++
    }

    private fun recompute() {
        if (histSize < MIN_HISTORY) return
        for (b in 0 until bins) {
            val h = history[b]
            var n = 0
            for (i in 0 until histSize) {
                val v = h[i]
                if (!v.isNaN()) scratch[n++] = v
            }
            if (n == 0) continue
            val k = ((n - 1) * PERCENTILE).roundToInt()
            profileDb[b] = select(scratch, n, k).toDouble()
        }
    }

    /** k-th smallest of the first [n] values (Hoare quickselect, in place) — O(n) instead of a sort per bin. */
    private fun select(a: FloatArray, n: Int, k: Int): Float {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) { val t = a[i]; a[i] = a[j]; a[j] = t; i++; j-- }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    companion object {
        const val INITIAL_DB = -100.0
        const val PERCENTILE = 0.10
        const val MIN_HISTORY = 10
        const val DEFAULT_WINDOW_SECONDS = 180.0
    }
}
