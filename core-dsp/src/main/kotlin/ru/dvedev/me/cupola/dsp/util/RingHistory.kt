package ru.dvedev.me.cupola.dsp.util

/**
 * Fixed-capacity history of doubles (oldest values fall off). `NaN` marks "no value"
 * (an unvoiced frame) and is skipped by the statistics. No allocation after construction.
 */
class RingHistory(val capacity: Int) {
    private val data = DoubleArray(capacity) { Double.NaN }
    private var head = 0 // next write slot
    var size = 0
        private set

    fun push(value: Double) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        data.fill(Double.NaN)
        head = 0
        size = 0
    }

    /** Value `i` steps back from the newest (`0` = newest). */
    operator fun get(i: Int): Double = data[Math.floorMod(head - 1 - i, capacity)]

    /** Oldest-first iteration index → value. */
    fun at(indexFromOldest: Int): Double = data[Math.floorMod(head - size + indexFromOldest, capacity)]

    fun validCount(): Int {
        var n = 0
        for (i in 0 until size) if (!at(i).isNaN()) n++
        return n
    }

    fun mean(): Double {
        var s = 0.0
        var n = 0
        for (i in 0 until size) {
            val v = at(i)
            if (!v.isNaN()) { s += v; n++ }
        }
        return if (n == 0) Double.NaN else s / n
    }

    /** Population standard deviation of valid values (NaN if fewer than [minValid]). */
    fun sd(minValid: Int = 2): Double {
        val m = mean()
        if (m.isNaN()) return Double.NaN
        var acc = 0.0
        var n = 0
        for (i in 0 until size) {
            val v = at(i)
            if (!v.isNaN()) { acc += (v - m) * (v - m); n++ }
        }
        return if (n < minValid) Double.NaN else kotlin.math.sqrt(acc / n)
    }

    /**
     * Least-squares slope of value against time, where consecutive slots are [stepSeconds]
     * apart. NaN if fewer than [minValid] valid points.
     */
    fun slope(stepSeconds: Double, minValid: Int = 2): Double {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until size) {
            val v = at(i)
            if (v.isNaN()) continue
            val x = i * stepSeconds
            n++; sx += x; sy += v; sxx += x * x; sxy += x * v
        }
        if (n < minValid) return Double.NaN
        val denom = n * sxx - sx * sx
        return if (denom == 0.0) Double.NaN else (n * sxy - sx * sy) / denom
    }

    /** Copies valid values oldest-first into [dst]; returns how many were written. */
    fun copyValidInto(dst: DoubleArray): Int {
        var n = 0
        for (i in 0 until size) {
            val v = at(i)
            if (!v.isNaN() && n < dst.size) dst[n++] = v
        }
        return n
    }

    /** Copies all values oldest-first (NaN included) into [dst]; returns count. */
    fun copyInto(dst: DoubleArray): Int {
        val n = minOf(size, dst.size)
        for (i in 0 until n) dst[i] = at(i)
        return n
    }
}
