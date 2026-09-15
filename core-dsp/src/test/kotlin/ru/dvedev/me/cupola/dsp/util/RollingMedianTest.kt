package ru.dvedev.me.cupola.dsp.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollingMedianTest {
    @Test
    fun `matches a sorted-window median`() {
        val rnd = Random(11)
        val cap = 21
        val rm = RollingMedian(cap)
        val window = ArrayDeque<Double>()
        repeat(2000) {
            val v = if (rnd.nextInt(10) == 0) Double.NaN else rnd.nextDouble() * 100
            rm.push(v)
            if (!v.isNaN()) {
                window.addLast(v)
                if (window.size > cap) window.removeFirst()
                val s = window.sorted()
                val expected = if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
                assertEquals(expected, rm.median(), 1e-12)
            }
        }
    }

    @Test
    fun `ring history statistics`() {
        val h = RingHistory(10)
        for (i in 0 until 10) h.push(i.toDouble())
        assertEquals(4.5, h.mean(), 1e-12)
        assertEquals(1.0, h.slope(1.0), 1e-12)
        h.push(Double.NaN)
        assertEquals(9, h.validCount())
        assertEquals(Double.NaN, h[0])
        assertEquals(9.0, h[1])
        assertTrue(h.sd() > 2.5)
    }
}
