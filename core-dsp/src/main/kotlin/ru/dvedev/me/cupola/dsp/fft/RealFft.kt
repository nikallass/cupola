package ru.dvedev.me.cupola.dsp.fft

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT of real input of length [n] (power of two), computed through a complex FFT
 * of length n/2 plus a split step. Output bins 0..n/2 land in [forward]'s `re`/`im`.
 * All tables are precomputed; [forward] does not allocate.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 4 && n and (n - 1) == 0) { "n must be a power of two ≥ 4, got $n" }
    }

    /** Number of output bins: DC … Nyquist. */
    val bins: Int = n / 2 + 1

    private val m = n / 2
    private val bitRev = IntArray(m).also { table ->
        var bits = 0
        while (1 shl bits < m) bits++
        for (i in 0 until m) {
            var r = 0
            var x = i
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            table[i] = r
        }
    }
    private val cosM = DoubleArray(m / 2) { cos(2 * PI * it / m) }
    private val sinM = DoubleArray(m / 2) { -sin(2 * PI * it / m) }
    private val cosN = DoubleArray(m) { cos(2 * PI * it / n) }
    private val sinN = DoubleArray(m) { -sin(2 * PI * it / n) }
    private val zr = DoubleArray(m)
    private val zi = DoubleArray(m)

    /**
     * Forward transform of [input] (length [n]). Writes `re[k]`, `im[k]` for `k = 0..n/2`
     * (arrays of length ≥ [bins]).
     */
    fun forward(input: DoubleArray, re: DoubleArray, im: DoubleArray) {
        // pack even samples into the real part, odd into the imaginary part, bit-reversed
        for (i in 0 until m) {
            val j = bitRev[i]
            zr[j] = input[2 * i]
            zi[j] = input[2 * i + 1]
        }
        // iterative radix-2 complex FFT of length m
        var len = 2
        while (len <= m) {
            val half = len / 2
            val step = m / len
            var start = 0
            while (start < m) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosM[k]
                    val wi = sinM[k]
                    val a = start + j
                    val b = a + half
                    val tr = zr[b] * wr - zi[b] * wi
                    val ti = zr[b] * wi + zi[b] * wr
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
        // split: X[k] = Fe[k] + W^k · Fo[k]
        re[0] = zr[0] + zi[0]
        im[0] = 0.0
        re[m] = zr[0] - zi[0]
        im[m] = 0.0
        for (k in 1 until m) {
            val zkr = zr[k]
            val zki = zi[k]
            val zmr = zr[m - k]
            val zmi = -zi[m - k] // conj(Z[m-k])
            val fer = 0.5 * (zkr + zmr)
            val fei = 0.5 * (zki + zmi)
            // Fo = -i/2 · (Z[k] - conj(Z[m-k]))
            val dr = zkr - zmr
            val di = zki - zmi
            val forRe = 0.5 * di
            val forIm = -0.5 * dr
            val wr = cosN[k]
            val wi = sinN[k]
            re[k] = fer + forRe * wr - forIm * wi
            im[k] = fei + forRe * wi + forIm * wr
        }
    }
}
