package ru.dvedev.me.cupola.dsp.fft

import ru.dvedev.me.cupola.dsp.frame.Windows
import ru.dvedev.me.cupola.testdata.Signals
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFftTest {
    @Test
    fun `matches a naive DFT`() {
        for (n in listOf(8, 64, 2048, 4096)) {
            val rnd = Random(n)
            val x = DoubleArray(n) { rnd.nextDouble() * 2 - 1 }
            val fft = RealFft(n)
            val re = DoubleArray(fft.bins)
            val im = DoubleArray(fft.bins)
            fft.forward(x, re, im)
            var maxErr = 0.0
            for (k in 0 until fft.bins) {
                var sr = 0.0
                var si = 0.0
                for (j in 0 until n) {
                    val a = -2 * PI * k * j / n
                    sr += x[j] * cos(a)
                    si += x[j] * sin(a)
                }
                maxErr = maxOf(maxErr, abs(sr - re[k]), abs(si - im[k]))
            }
            assertTrue(maxErr < 1e-6 * n, "n=$n maxErr=$maxErr")
        }
    }

    @Test
    fun `1 kHz sine peaks in the right bin at 0 dBFS`() {
        val n = 2048
        val fs = 48_000
        val ps = PowerSpectrum(n, fs)
        val binHz = fs.toDouble() / n
        val hz = 43 * binHz // exactly on a bin: 1007.8 Hz
        val x = Signals.sine(hz, n.toDouble() / fs, fs, amplitude = 1.0)
        val w = Windows.hann(n)
        ps.compute(DoubleArray(n) { x[it] * w[it] })
        assertEquals(43, ps.peakBin(0.0, fs / 2.0))
        assertEquals(0.0, ps.db[43], 0.05)
        assertEquals(hz, ps.refinePeakHz(43), 0.5)
        assertTrue(ps.db[40] < -60.0 && ps.db[46] < -60.0, "Hann leakage")
    }

    @Test
    fun `off-bin sine is refined by interpolation`() {
        val n = 2048
        val fs = 48_000
        val ps = PowerSpectrum(n, fs)
        val x = Signals.sine(1000.0, n.toDouble() / fs, fs, amplitude = 0.5)
        val w = Windows.hann(n)
        ps.compute(DoubleArray(n) { x[it] * w[it] })
        val bin = ps.peakBin(500.0, 2000.0)
        assertEquals(1000.0, ps.refinePeakHz(bin), 1.0)
        assertEquals(-6.02, ps.peakDb(500.0, 2000.0), 1.5) // scalloping loss ≤ 1.4 dB for Hann
    }

    @Test
    fun `2048-point FFT is fast`() {
        val n = 2048
        val fft = RealFft(n)
        val x = DoubleArray(n) { sin(it * 0.1) }
        val re = DoubleArray(fft.bins)
        val im = DoubleArray(fft.bins)
        repeat(2000) { fft.forward(x, re, im) } // warm-up
        val iterations = 5000
        val t0 = System.nanoTime()
        repeat(iterations) { fft.forward(x, re, im) }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / iterations
        println("RealFft(2048): %.3f ms per call".format(perCallMs))
        assertTrue(perCallMs < 2.0, "too slow: $perCallMs ms")
    }
}
