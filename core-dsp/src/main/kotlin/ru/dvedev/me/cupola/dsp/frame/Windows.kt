package ru.dvedev.me.cupola.dsp.frame

import kotlin.math.PI
import kotlin.math.cos

object Windows {
    /** Periodic Hann window (the DFT-friendly variant). */
    fun hann(n: Int): DoubleArray = DoubleArray(n) { i -> 0.5 - 0.5 * cos(2 * PI * i / n) }

    fun sum(window: DoubleArray): Double {
        var s = 0.0
        for (w in window) s += w
        return s
    }
}
