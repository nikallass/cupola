package ru.dvedev.me.cupola.notation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchTest {
    private fun assertPitch(hz: Double, expectedEn: String, expectedCents: Double, a4: Double = 440.0, tol: Double = 0.5) {
        val p = nearestNote(hz, a4)
        assertEquals(expectedEn, NoteNames.en(p.note), "note for $hz Hz")
        assertEquals(expectedCents, p.cents, tol, "cents for $hz Hz")
    }

    @Test
    fun `440 Hz is A4 at 0 cents`() = assertPitch(440.0, "A4", 0.0, tol = 1e-9)

    @Test
    fun `261_63 Hz is C4`() = assertPitch(261.63, "C4", 0.0, tol = 0.1)

    @Test
    fun `226 Hz is A3 plus 46 cents`() = assertPitch(226.0, "A3", 46.0, tol = 1.0)

    @Test
    fun `octave boundary between B3 and C4`() {
        assertPitch(246.94, "B3", 0.0, tol = 0.1)
        assertPitch(254.0, "B3", 49.0, tol = 1.0)
        assertPitch(255.0, "C4", -45.0, tol = 1.0)
    }

    @Test
    fun `exact midpoint reports plus 50 of the lower note`() {
        val midpointHz = midiToHz(60.5)
        val p = nearestNote(midpointHz)
        assertEquals(60, p.midi)
        assertEquals(50.0, p.cents, 1e-9)
    }

    @Test
    fun `cents are always above -50 and at most +50`() {
        var hz = 30.0
        while (hz < 8000.0) {
            val p = nearestNote(hz)
            assertTrue(p.cents > -50.0 && p.cents <= 50.0, "$hz Hz → ${p.cents}")
            hz *= 1.003
        }
    }

    @Test
    fun `a4 = 432 shifts the reference`() {
        assertPitch(432.0, "A4", 0.0, a4 = 432.0, tol = 1e-9)
        assertPitch(440.0, "A4", 31.8, a4 = 432.0, tol = 0.2)
        assertPitch(261.63, "C4", 31.8, a4 = 432.0, tol = 0.3)
    }

    @Test
    fun `midi to hz round trip`() {
        for (midi in 0..127) {
            val hz = midiToHz(midi)
            assertEquals(midi.toDouble(), hzToMidi(hz), 1e-9)
            assertEquals(midi, nearestNote(hz).midi)
        }
        assertEquals(440.0, midiToHz(69), 1e-12)
        assertEquals(261.6256, midiToHz(60), 1e-4)
    }

    @Test
    fun `cents between frequencies`() {
        assertEquals(1200.0, centsBetween(880.0, 440.0), 1e-9)
        assertEquals(-100.0, centsBetween(midiToHz(68), 440.0), 1e-9)
    }

    @Test
    fun `note octave numbering is scientific`() {
        assertEquals(4, Note(60).octave)
        assertEquals(3, Note(59).octave)
        assertEquals(-1, Note(0).octave)
        assertEquals(PitchClass.B, Note(59).pitchClass)
        assertEquals(Note(60), Note.of(PitchClass.C, 4))
        assertEquals(Note(69), Note.A4)
    }
}
