package ru.dvedev.me.cupola.testdata

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Fundamental frequency as a function of time, for synthetic voices. */
sealed interface PitchContour {
    fun hzAt(t: Double): Double

    data class Constant(val hz: Double) : PitchContour {
        override fun hzAt(t: Double): Double = hz
    }

    /** Sinusoidal vibrato: `hz · 2^(extent·sin(2π·rate·t) / 1200)`; [extentCents] is the half-swing. */
    data class Vibrato(val hz: Double, val rateHz: Double, val extentCents: Double, val phase: Double = 0.0) : PitchContour {
        override fun hzAt(t: Double): Double = hz * 2.0.pow(extentCents * sin(2 * PI * rateHz * t + phase) / 1200.0)
    }

    /** Linear-in-cents glide: `start · 2^(centsPerSecond·t / 1200)`. */
    data class Glissando(val startHz: Double, val centsPerSecond: Double) : PitchContour {
        override fun hzAt(t: Double): Double = startHz * 2.0.pow(centsPerSecond * t / 1200.0)
    }

    class Custom(private val f: (Double) -> Double) : PitchContour {
        override fun hzAt(t: Double): Double = f(t)
    }
}
