package ru.dvedev.me.cupola.dsp.score

import kotlin.math.exp

/** One-pole smoother with separate time constants for rising and falling input. */
class AttackRelease(attackSeconds: Double, releaseSeconds: Double, hopSeconds: Double) {
    private val attack = 1.0 - exp(-hopSeconds / attackSeconds)
    private val release = 1.0 - exp(-hopSeconds / releaseSeconds)

    var value: Double = 0.0
        private set

    fun process(x: Double): Double {
        val a = if (x > value) attack else release
        value += (x - value) * a
        return value
    }

    fun reset(to: Double = 0.0) {
        value = to
    }
}
