package ru.dvedev.me.cupola.dsp.util

/**
 * Median of the last [capacity] valid values. Intended for short windows (≤ 64): keeps
 * a sorted copy and inserts/removes in O(n), no allocation after construction.
 * `NaN` inputs are ignored (they neither enter nor evict).
 */
class RollingMedian(val capacity: Int) {
    private val queue = DoubleArray(capacity)
    private val sorted = DoubleArray(capacity)
    private var head = 0
    var size = 0
        private set

    fun push(value: Double) {
        if (value.isNaN()) return
        if (size == capacity) {
            remove(queue[head])
            queue[head] = value
            head = (head + 1) % capacity
        } else {
            queue[(head + size) % capacity] = value
            size++
        }
        insert(value)
    }

    fun clear() {
        head = 0
        size = 0
    }

    /** NaN until at least [minSize] values were pushed. */
    fun median(minSize: Int = 1): Double {
        if (size < minSize || size == 0) return Double.NaN
        return if (size % 2 == 1) sorted[size / 2] else 0.5 * (sorted[size / 2 - 1] + sorted[size / 2])
    }

    private fun insert(value: Double) {
        var i = size - 1 // size already incremented or slot freed by remove
        while (i > 0 && sorted[i - 1] > value) {
            sorted[i] = sorted[i - 1]
            i--
        }
        sorted[i] = value
    }

    private fun remove(value: Double) {
        var i = 0
        while (i < size && sorted[i] != value) i++
        // shift left, leaving the last slot free for the incoming value
        while (i < size - 1) {
            sorted[i] = sorted[i + 1]
            i++
        }
    }
}
