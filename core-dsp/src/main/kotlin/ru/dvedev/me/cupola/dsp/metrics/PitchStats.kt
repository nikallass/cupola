package ru.dvedev.me.cupola.dsp.metrics

import ru.dvedev.me.cupola.dsp.util.RingHistory
import ru.dvedev.me.cupola.dsp.util.RollingMedian
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pitch contour statistics (SPEC §5.1):
 *
 * - **median line** — rolling median over [medianSeconds] of the cents contour, then a
 *   moving average of the same length to remove the residual ripple a median leaves on a
 *   vibrato whose period does not divide the window. Vibrato does not reach the line.
 * - [pitchSd] — SD of the median line over [sdSeconds].
 * - [drift] — least-squares slope of the median line over [driftSeconds], ¢/s.
 * - [residual] — `cents − medianLine`, the input of the vibrato analyser.
 *
 * Cents are absolute (`1200·log2(f0)`), so the contour is continuous across note boundaries.
 */
class PitchStats(
    val hopSeconds: Double,
    val medianSeconds: Double = 0.2,
    val sdSeconds: Double = 0.5,
    val driftSeconds: Double = 2.0,
) {
    private val medianFrames = (medianSeconds / hopSeconds).roundToInt().coerceAtLeast(3)
    private val sdFrames = (sdSeconds / hopSeconds).roundToInt().coerceAtLeast(3)
    private val driftFrames = (driftSeconds / hopSeconds).roundToInt().coerceAtLeast(3)

    private val median = RollingMedian(medianFrames)
    private val medianSmooth = RingHistory(medianFrames)
    private val line = RingHistory(driftFrames)
    private val lineShort = RingHistory(sdFrames)
    private var unvoicedRun = 0

    /** Current median-line value in absolute cents, NaN if not enough voiced frames. */
    var medianCents: Double = Double.NaN
        private set

    /** Current raw contour value in absolute cents (NaN when unvoiced). */
    var cents: Double = Double.NaN
        private set

    /** `cents − medianCents`, NaN when either is missing. */
    val residual: Double get() = cents - medianCents

    val pitchSd: Double get() = lineShort.sd(minValid = sdFrames / 2)

    val drift: Double get() = line.slope(hopSeconds, minValid = driftFrames / 2)

    /** Push one frame; [f0Hz] ≤ 0 means unvoiced. */
    fun push(f0Hz: Double) {
        cents = if (f0Hz > 0) absoluteCents(f0Hz) else Double.NaN
        if (cents.isNaN()) {
            if (++unvoicedRun >= medianFrames) {
                // a real gap: forget the old contour so the next phrase starts clean
                median.clear()
                medianSmooth.clear()
            }
            medianCents = Double.NaN
        } else {
            unvoicedRun = 0
            median.push(cents)
            val m = median.median(minSize = medianFrames / 2)
            if (!m.isNaN()) medianSmooth.push(m)
            medianCents = if (medianSmooth.validCount() >= medianFrames / 2) medianSmooth.mean() else Double.NaN
        }
        line.push(medianCents)
        lineShort.push(medianCents)
    }

    fun reset() {
        median.clear()
        medianSmooth.clear()
        line.clear()
        lineShort.clear()
        unvoicedRun = 0
        medianCents = Double.NaN
        cents = Double.NaN
    }

    companion object {
        fun absoluteCents(hz: Double): Double = 1200.0 * ln(hz) / ln(2.0)
    }
}
