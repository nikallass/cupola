package ru.dvedev.me.cupola.dsp.frame

import kotlin.test.Test
import kotlin.test.assertEquals

class FloatRingBufferTest {
    @Test
    fun `round trip across the wrap point`() {
        val rb = FloatRingBuffer(1000) // rounds up to 1024
        assertEquals(1024, rb.capacity)
        val src = FloatArray(700) { it.toFloat() }
        val dst = FloatArray(700)
        repeat(5) { round ->
            assertEquals(700, rb.write(src))
            assertEquals(700, rb.available())
            assertEquals(700, rb.read(dst))
            for (i in 0 until 700) assertEquals(i.toFloat(), dst[i], "round $round, i=$i")
            assertEquals(0, rb.available())
        }
        assertEquals(0, rb.overruns)
    }

    @Test
    fun `overrun drops the newest samples and counts them`() {
        val rb = FloatRingBuffer(256)
        val src = FloatArray(300) { it.toFloat() }
        assertEquals(256, rb.write(src))
        assertEquals(44, rb.overruns)
        val dst = FloatArray(256)
        assertEquals(256, rb.read(dst))
        assertEquals(255f, dst[255])
    }

    @Test
    fun `concurrent producer and consumer keep sample order`() {
        val rb = FloatRingBuffer(4096)
        val total = 2_000_000
        val consumer = Thread {
            val dst = FloatArray(1024)
            var expected = 0
            while (expected < total) {
                val n = rb.read(dst)
                for (i in 0 until n) {
                    check(dst[i] == expected.toFloat()) { "out of order at $expected" }
                    expected++
                }
                if (n == 0) Thread.onSpinWait()
            }
        }
        consumer.start()
        val src = FloatArray(480)
        var produced = 0
        while (produced < total) {
            val n = minOf(480, total - produced)
            for (i in 0 until n) src[i] = (produced + i).toFloat()
            var written = 0
            while (written < n) {
                written += rb.write(src, written, n - written)
                if (written < n) Thread.onSpinWait()
            }
            produced += n
        }
        consumer.join(10_000)
        assertEquals(false, consumer.isAlive)
    }
}
