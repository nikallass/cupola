package ru.dvedev.me.cupola.notation

/** Twelve pitch classes; ordinal is the semitone offset from C. */
enum class PitchClass(val isBlackKey: Boolean) {
    C(false), CS(true), D(false), DS(true), E(false), F(false),
    FS(true), G(false), GS(true), A(false), AS(true), B(false);

    companion object {
        fun of(midi: Int): PitchClass = entries[Math.floorMod(midi, 12)]
    }
}

/** Whether black keys are spelled as sharps (`C♯`) or flats (`D♭`). */
enum class Accidentals { SHARPS, FLATS }

/**
 * An equal-tempered note identified by its MIDI number. Scientific octave numbering:
 * `C4 = 60` is middle C (до первой октавы), octave changes between B and C.
 */
@JvmInline
value class Note(val midi: Int) {
    val pitchClass: PitchClass get() = PitchClass.of(midi)

    /** Scientific octave number: C4 = 4, B3 = 3, C-1 = -1. */
    val octave: Int get() = Math.floorDiv(midi, 12) - 1

    fun hz(a4Hz: Double = Tuning.DEFAULT_A4_HZ): Double = midiToHz(midi, a4Hz)

    operator fun plus(semitones: Int): Note = Note(midi + semitones)
    operator fun minus(semitones: Int): Note = Note(midi - semitones)

    companion object {
        fun of(pitchClass: PitchClass, octave: Int): Note = Note((octave + 1) * 12 + pitchClass.ordinal)
        val A4: Note = Note(Tuning.MIDI_A4)
        val C4: Note = Note(60)
    }
}
