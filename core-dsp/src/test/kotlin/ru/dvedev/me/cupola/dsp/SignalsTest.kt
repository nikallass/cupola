package ru.dvedev.me.cupola.dsp

import ru.dvedev.me.cupola.testdata.PitchContour
import ru.dvedev.me.cupola.testdata.Signals
import ru.dvedev.me.cupola.testdata.VoiceSpec
import ru.dvedev.me.cupola.testdata.Wav
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sanity checks of the `:core-testdata` generators themselves. */
class SignalsTest {
    @Test
    fun `sine amplitude and length`() {
        val s = Signals.sine(440.0, 1.0, amplitude = 0.5)
        assertEquals(48_000, s.size)
        assertEquals(0.5 / kotlin.math.sqrt(2.0), Signals.rms(s), 1e-3)
    }

    @Test
    fun `gain changes level by the stated dB`() {
        val s = Signals.sine(440.0, 0.5)
        assertEquals(Signals.rmsDb(s) + 10.0, Signals.rmsDb(Signals.gain(s, 10.0)), 1e-9)
    }

    @Test
    fun `noise rms and pink noise energy`() {
        assertEquals(0.1, Signals.rms(Signals.whiteNoise(1.0, rms = 0.1)), 0.005)
        assertEquals(0.1, Signals.rms(Signals.pinkNoise(1.0, rms = 0.1)), 1e-6)
    }

    @Test
    fun `harmonic voice peaks at the requested amplitude`() {
        val v = Signals.harmonicVoice(VoiceSpec(PitchContour.Constant(220.0), amplitude = 0.3), 0.5)
        assertEquals(0.3, v.maxOf { kotlin.math.abs(it) }, 1e-9)
    }

    @Test
    fun `wav writer produces a valid header`() {
        val file = File.createTempFile("cupola-", ".wav")
        try {
            val s = Signals.sine(440.0, 0.1)
            Wav.write(file, s)
            val bytes = file.readBytes()
            assertEquals(44 + s.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertTrue(bytes.size > 44)
        } finally {
            file.delete()
        }
    }
}
