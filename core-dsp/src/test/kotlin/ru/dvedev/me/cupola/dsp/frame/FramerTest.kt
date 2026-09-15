package ru.dvedev.me.cupola.dsp.frame

import ru.dvedev.me.cupola.testdata.Signals
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramerTest {
    private val fs = 48_000
    private val frame = 2048
    private val hop = 480

    @Test
    fun `frame count for N samples`() {
        val signal = Signals.sine(220.0, 1.0, fs)
        val framer = Framer(frame, hop, fs)
        var count = 0
        framer.push(signal) { count++ }
        assertEquals(Framer.frameCount(signal.size.toLong(), frame, hop), count.toLong())
        assertEquals(((48_000 - 2048) / 480 + 1).toLong(), count.toLong())
    }

    @Test
    fun `ragged feeding yields the same frames as bulk feeding`() {
        val signal = Signals.sawtooth(200.0, 0.5, fs)
        val bulk = collect(signal, listOf(signal.size))
        val rnd = Random(7)
        val chunks = buildList {
            var left = signal.size
            while (left > 0) {
                val c = minOf(left, 1 + rnd.nextInt(700))
                add(c)
                left -= c
            }
        }
        val ragged = collect(signal, chunks)
        assertEquals(bulk.size, ragged.size)
        for (i in bulk.indices) assertContentEquals(bulk[i], ragged[i], "frame $i")
    }

    @Test
    fun `frame timing and windowing`() {
        val signal = Signals.sine(220.0, 0.2, fs)
        val framer = Framer(frame, hop, fs)
        val times = mutableListOf<Double>()
        framer.push(signal) { f ->
            times += f.endSeconds
            assertEquals(0.0, f.windowed[0], 1e-12) // Hann starts at zero
            assertTrue(f.raw[frame / 2] != 0.0 || f.raw[frame / 2 + 1] != 0.0)
        }
        assertEquals(frame.toDouble() / fs, times[0], 1e-12)
        assertEquals(hop.toDouble() / fs, times[1] - times[0], 1e-12)
    }

    @Test
    fun `float and double inputs agree`() {
        val signal = Signals.sine(330.0, 0.3, fs)
        val asFloat = FloatArray(signal.size) { signal[it].toFloat() }
        val a = collect(signal, listOf(signal.size))
        val framer = Framer(frame, hop, fs)
        val b = mutableListOf<DoubleArray>()
        framer.push(asFloat) { b += it.raw.copyOf() }
        assertEquals(a.size, b.size)
        for (i in a.indices) for (j in 0 until frame) assertEquals(a[i][j], b[i][j], 1e-6)
    }

    private fun collect(signal: DoubleArray, chunks: List<Int>): List<DoubleArray> {
        val framer = Framer(frame, hop, fs)
        val out = mutableListOf<DoubleArray>()
        var pos = 0
        for (c in chunks) {
            framer.push(signal, pos, c) { out += it.raw.copyOf() }
            pos += c
        }
        return out
    }
}
