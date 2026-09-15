package ru.dvedev.me.cupola.dsp.frame

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free single-producer / single-consumer ring buffer of PCM floats.
 *
 * The audio thread writes, the analysis thread reads; neither allocates. If the consumer
 * falls behind, the newest samples are dropped and [overruns] counts them so the UI can
 * show a frame-drop indicator instead of silently desynchronising.
 */
class FloatRingBuffer(capacity: Int) {
    val capacity: Int = Integer.highestOneBit(capacity - 1) shl 1
    private val mask = this.capacity - 1
    private val data = FloatArray(this.capacity)
    private val head = AtomicLong(0) // next write position (producer)
    private val tail = AtomicLong(0) // next read position (consumer)

    @Volatile
    var overruns: Long = 0
        private set

    fun available(): Int = (head.get() - tail.get()).toInt()

    fun free(): Int = capacity - available()

    /** Producer side. Returns the number of samples actually stored. */
    fun write(src: FloatArray, offset: Int = 0, length: Int = src.size - offset): Int {
        val h = head.get()
        val space = capacity - (h - tail.get()).toInt()
        val n = minOf(length, space)
        if (n < length) overruns += (length - n)
        val start = (h and mask.toLong()).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(src, offset, data, start, first)
        if (n > first) System.arraycopy(src, offset + first, data, 0, n - first)
        head.set(h + n)
        return n
    }

    /** Consumer side. Returns the number of samples copied into [dst]. */
    fun read(dst: FloatArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        val t = tail.get()
        val n = minOf(length, (head.get() - t).toInt())
        val start = (t and mask.toLong()).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(data, start, dst, offset, first)
        if (n > first) System.arraycopy(data, 0, dst, offset + first, n - first)
        tail.set(t + n)
        return n
    }

    fun clear() {
        tail.set(head.get())
    }
}
