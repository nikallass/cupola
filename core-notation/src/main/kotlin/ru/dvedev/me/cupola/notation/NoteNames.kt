package ru.dvedev.me.cupola.notation

import kotlin.math.abs
import kotlin.math.roundToInt

/** Which naming the user wants on screen (settings → «Нотация»); default is both. */
enum class NotationMode { RU, EN, BOTH }

const val SHARP = "♯"
const val FLAT = "♭"
const val MINUS = "−" // U+2212, not a hyphen
const val CENT = "¢"
const val MIDDLE_DOT = " · "

/**
 * Two-layer label of a note: [ru] is the singer-facing name (`Соль¹`), [en] the scientific
 * one (`G4`). Either may be null depending on [NotationMode]; [joined] is `Соль¹ · G4`.
 */
data class NoteLabel(val ru: String?, val en: String?) {
    val joined: String get() = listOfNotNull(ru, en).joinToString(MIDDLE_DOT)
}

object NoteNames {
    private val EN_SHARP = arrayOf("C", "C$SHARP", "D", "D$SHARP", "E", "F", "F$SHARP", "G", "G$SHARP", "A", "A$SHARP", "B")
    private val EN_FLAT = arrayOf("C", "D$FLAT", "D", "E$FLAT", "E", "F", "G$FLAT", "G", "A$FLAT", "A", "B$FLAT", "B")

    private val RU_BASE = arrayOf("до", "до", "ре", "ре", "ми", "фа", "фа", "соль", "соль", "ля", "ля", "си")
    private val RU_BASE_FLAT = arrayOf("до", "ре", "ре", "ми", "ми", "фа", "соль", "соль", "ля", "ля", "си", "си")

    /** Superscript digits for octaves 1–5 (scientific 4–8), SPEC §7 table. */
    private val RU_SUPERSCRIPT = mapOf(4 to "¹", 5 to "²", 6 to "³", 7 to "⁴", 8 to "⁵")

    /** Genitive octave names used after the note: «ля малой», «до большой». */
    private val RU_OCTAVE_COMPACT = mapOf(
        0 to "субконтроктавы", 1 to "контроктавы", 2 to "большой", 3 to "малой",
    )

    /** Full octave names for the spoken / accessibility form: «ля первой октавы». */
    private val RU_OCTAVE_FULL = mapOf(
        0 to "субконтроктавы", 1 to "контроктавы", 2 to "большой октавы", 3 to "малой октавы",
        4 to "первой октавы", 5 to "второй октавы", 6 to "третьей октавы", 7 to "четвёртой октавы", 8 to "пятой октавы",
    )

    /** Scientific name: `C4`, `C♯4`, `D♭4`. */
    fun en(note: Note, accidentals: Accidentals = Accidentals.SHARPS): String {
        val names = if (accidentals == Accidentals.SHARPS) EN_SHARP else EN_FLAT
        return names[note.pitchClass.ordinal] + note.octave
    }

    /** Scientific name with ASCII accidentals for logs and exports: `C#4`, `Db4`. */
    fun enAscii(note: Note, accidentals: Accidentals = Accidentals.SHARPS): String =
        en(note, accidentals).replace(SHARP, "#").replace(FLAT, "b")

    /**
     * Compact Russian name: `Ля¹`, `Ля♯¹`, `Ля малой`, `До большой`, `Ля контроктавы`.
     * Capitalised because it is the headline of the note zone.
     */
    fun ru(note: Note, accidentals: Accidentals = Accidentals.SHARPS): String {
        val base = ruBase(note, accidentals).replaceFirstChar { it.uppercase() }
        val sign = ruSign(note, accidentals)
        val octave = note.octave
        return when {
            octave in RU_SUPERSCRIPT -> base + sign + RU_SUPERSCRIPT.getValue(octave)
            octave in RU_OCTAVE_COMPACT -> "$base$sign ${RU_OCTAVE_COMPACT.getValue(octave)}"
            else -> "$base$sign $octave" // outside the singing range (C-1…, C9…); scientific number as fallback
        }
    }

    /**
     * Shortest Russian form for the headline (Helmholtz-style case and digits): «Ля¹»
     * (первая), «ля» (малая, lowercase), «Ля» (большая), «Ля₁» (контроктава), «Ля₂»
     * (субконтроктава). Always shown next to the scientific name, which resolves the case.
     */
    fun ruShort(note: Note, accidentals: Accidentals = Accidentals.SHARPS): String {
        val base = ruBase(note, accidentals)
        val sign = ruSign(note, accidentals)
        val cap = base.replaceFirstChar { it.uppercase() }
        return when (val octave = note.octave) {
            in RU_SUPERSCRIPT -> cap + sign + RU_SUPERSCRIPT.getValue(octave)
            3 -> base + sign
            2 -> cap + sign
            1 -> cap + sign + "₁"
            0 -> cap + sign + "₂"
            else -> "$cap$sign $octave"
        }
    }

    /**
     * Full spoken Russian name for accessibility: «ля первой октавы», «ля-диез первой
     * октавы», «си-бемоль малой октавы».
     */
    fun ruFull(note: Note, accidentals: Accidentals = Accidentals.SHARPS): String {
        val base = ruBase(note, accidentals)
        val word = when {
            !note.pitchClass.isBlackKey -> base
            accidentals == Accidentals.SHARPS -> "$base-диез"
            else -> "$base-бемоль"
        }
        val octave = RU_OCTAVE_FULL[note.octave] ?: "октавы ${note.octave}"
        return "$word $octave"
    }

    fun label(note: Note, mode: NotationMode = NotationMode.BOTH, accidentals: Accidentals = Accidentals.SHARPS): NoteLabel =
        NoteLabel(
            ru = if (mode != NotationMode.EN) ru(note, accidentals) else null,
            en = if (mode != NotationMode.RU) en(note, accidentals) else null,
        )

    /** `+12 ¢`, `−31 ¢`, `0 ¢` — rounded to whole cents, Unicode minus, thin gap before the sign. */
    fun cents(cents: Double): String {
        val c = cents.roundToInt()
        val sign = when {
            c > 0 -> "+"
            c < 0 -> MINUS
            else -> ""
        }
        return "$sign${abs(c)} $CENT"
    }

    /** Spoken deviation for accessibility: «на 12 центов выше», «точно». */
    fun centsSpoken(cents: Double): String {
        val c = cents.roundToInt()
        if (c == 0) return "точно"
        val n = abs(c)
        val word = when {
            n % 100 in 11..19 -> "центов"
            n % 10 == 1 -> "цент"
            n % 10 in 2..4 -> "цента"
            else -> "центов"
        }
        return "на $n $word ${if (c > 0) "выше" else "ниже"}"
    }

    private fun ruBase(note: Note, accidentals: Accidentals): String =
        (if (accidentals == Accidentals.SHARPS) RU_BASE else RU_BASE_FLAT)[note.pitchClass.ordinal]

    private fun ruSign(note: Note, accidentals: Accidentals): String = when {
        !note.pitchClass.isBlackKey -> ""
        accidentals == Accidentals.SHARPS -> SHARP
        else -> FLAT
    }
}
