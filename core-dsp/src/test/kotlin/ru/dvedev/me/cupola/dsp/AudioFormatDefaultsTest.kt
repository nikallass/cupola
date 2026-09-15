package ru.dvedev.me.cupola.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioFormatDefaultsTest {
    @Test
    fun `hop is 10 ms at 48 kHz`() {
        assertEquals(0.010, AudioFormatDefaults.hopSeconds(), 1e-12)
    }

    @Test
    fun `fft size is a power of two`() {
        val n = AudioFormatDefaults.FFT_SIZE
        assertTrue(n > 0 && n and (n - 1) == 0)
    }
}
