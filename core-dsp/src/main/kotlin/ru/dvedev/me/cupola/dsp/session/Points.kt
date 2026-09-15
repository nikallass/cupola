package ru.dvedev.me.cupola.dsp.session

import kotlin.math.roundToInt

/**
 * Points («очки», never «монеты») of a session (SPEC §15.3, amended 2026‑09‑15: points
 * follow the cupola itself, not the mixed score): while `ring ≥ minRing` a portion of
 * `1 + round(2·ring)` is awarded every `0.6 − 0.48·ring` seconds — the more of the sound
 * sits in the cupola, the faster they come.
 */
class PointsCounter(
    val minRing: Double = 0.5,
    val baseIntervalSec: Double = 0.6,
    val intervalSlope: Double = 0.48,
) {
    var total: Int = 0
        private set

    private var nextAwardAt = Double.NaN

    fun intervalFor(ring: Double): Double = baseIntervalSec - intervalSlope * ring.coerceIn(0.0, 1.0)

    fun portionFor(ring: Double): Int = 1 + (2.0 * ring.coerceIn(0.0, 1.0)).roundToInt()

    /** Returns the points awarded at this instant (0 most of the time). */
    fun update(timeSec: Double, ring: Double): Int {
        if (ring < minRing) {
            nextAwardAt = Double.NaN
            return 0
        }
        if (nextAwardAt.isNaN()) {
            // just crossed the threshold: first portion after one interval
            nextAwardAt = timeSec + intervalFor(ring)
            return 0
        }
        if (timeSec < nextAwardAt) return 0
        val portion = portionFor(ring)
        total += portion
        nextAwardAt = timeSec + intervalFor(ring)
        return portion
    }

    fun reset() {
        total = 0
        nextAwardAt = Double.NaN
    }
}
