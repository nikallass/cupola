package ru.dvedev.me.cupola.notation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteNamesTest {
    @Test
    fun `SPEC section 7 octave table`() {
        // MIDI octave → compact RU, full RU (C of each octave)
        val rows = listOf(
            Triple(0, "До субконтроктавы", "до субконтроктавы"),
            Triple(1, "До контроктавы", "до контроктавы"),
            Triple(2, "До большой", "до большой октавы"),
            Triple(3, "До малой", "до малой октавы"),
            Triple(4, "До¹", "до первой октавы"),
            Triple(5, "До²", "до второй октавы"),
            Triple(6, "До³", "до третьей октавы"),
            Triple(7, "До⁴", "до четвёртой октавы"),
            Triple(8, "До⁵", "до пятой октавы"),
        )
        for ((octave, compact, full) in rows) {
            val note = Note.of(PitchClass.C, octave)
            assertEquals("C$octave", NoteNames.en(note))
            assertEquals(compact, NoteNames.ru(note), "compact C$octave")
            assertEquals(full, NoteNames.ruFull(note), "full C$octave")
        }
    }

    @Test
    fun `examples from the spec`() {
        assertEquals("Ля¹", NoteNames.ru(Note.A4))
        assertEquals("A4", NoteNames.en(Note.A4))
        assertEquals("Ля большой", NoteNames.ru(Note.of(PitchClass.A, 2)))
        assertEquals("Ля малой", NoteNames.ru(Note.of(PitchClass.A, 3)))
        assertEquals("ля первой октавы", NoteNames.ruFull(Note.A4))
        assertEquals("Соль¹", NoteNames.ru(Note.of(PitchClass.G, 4)))
    }

    @Test
    fun `accidentals spelling`() {
        val cs4 = Note.of(PitchClass.CS, 4)
        assertEquals("C♯4", NoteNames.en(cs4))
        assertEquals("D♭4", NoteNames.en(cs4, Accidentals.FLATS))
        assertEquals("C#4", NoteNames.enAscii(cs4))
        assertEquals("Db4", NoteNames.enAscii(cs4, Accidentals.FLATS))
        assertEquals("До♯¹", NoteNames.ru(cs4))
        assertEquals("Ре♭¹", NoteNames.ru(cs4, Accidentals.FLATS))
        assertEquals("до-диез первой октавы", NoteNames.ruFull(cs4))
        assertEquals("ре-бемоль первой октавы", NoteNames.ruFull(cs4, Accidentals.FLATS))
        assertEquals("Си♭ малой", NoteNames.ru(Note.of(PitchClass.AS, 3), Accidentals.FLATS))
    }

    @Test
    fun `notation mode selects layers`() {
        val g4 = Note.of(PitchClass.G, 4)
        assertEquals("Соль¹ · G4", NoteNames.label(g4).joined)
        assertEquals("Соль¹", NoteNames.label(g4, NotationMode.RU).joined)
        assertNull(NoteNames.label(g4, NotationMode.RU).en)
        assertEquals("G4", NoteNames.label(g4, NotationMode.EN).joined)
        assertNull(NoteNames.label(g4, NotationMode.EN).ru)
    }

    @Test
    fun `cents formatting`() {
        assertEquals("+12 ¢", NoteNames.cents(12.2))
        assertEquals("−31 ¢", NoteNames.cents(-31.4))
        assertEquals("0 ¢", NoteNames.cents(0.3))
        assertEquals("+50 ¢", NoteNames.cents(50.0))
    }

    @Test
    fun `cents spoken`() {
        assertEquals("точно", NoteNames.centsSpoken(0.4))
        assertEquals("на 1 цент выше", NoteNames.centsSpoken(1.0))
        assertEquals("на 2 цента ниже", NoteNames.centsSpoken(-2.0))
        assertEquals("на 12 центов выше", NoteNames.centsSpoken(12.0))
        assertEquals("на 21 цент ниже", NoteNames.centsSpoken(-21.0))
        assertEquals("на 31 цент ниже", NoteNames.centsSpoken(-31.0))
    }
}
