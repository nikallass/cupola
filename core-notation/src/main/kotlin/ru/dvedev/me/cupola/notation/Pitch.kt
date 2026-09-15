package ru.dvedev.me.cupola.notation

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Concert pitch reference. A4 is user-adjustable within [MIN_A4]..[MAX_A4] Hz (SPEC §7). */
object Tuning {
    const val DEFAULT_A4_HZ = 440.0
    const val MIN_A4 = 415.0
    const val MAX_A4 = 466.0
    const val MIDI_A4 = 69

    fun clampA4(a4Hz: Double): Double = a4Hz.coerceIn(MIN_A4, MAX_A4)
}

private const val LN2 = 0.6931471805599453

/** Fractional MIDI note number of a frequency; A4 ↦ 69. `hz` must be positive. */
fun hzToMidi(hz: Double, a4Hz: Double = Tuning.DEFAULT_A4_HZ): Double {
    require(hz > 0) { "frequency must be positive, got $hz" }
    return Tuning.MIDI_A4 + 12.0 * ln(hz / a4Hz) / LN2
}

/** Frequency of a (possibly fractional) MIDI note number. */
fun midiToHz(midi: Double, a4Hz: Double = Tuning.DEFAULT_A4_HZ): Double =
    a4Hz * 2.0.pow((midi - Tuning.MIDI_A4) / 12.0)

fun midiToHz(midi: Int, a4Hz: Double = Tuning.DEFAULT_A4_HZ): Double = midiToHz(midi.toDouble(), a4Hz)

/** Ratio between two frequencies in cents (positive when `hz` is above `refHz`). */
fun centsBetween(hz: Double, refHz: Double): Double = 1200.0 * ln(hz / refHz) / LN2

/**
 * Nearest equal-tempered note and the deviation from it.
 *
 * [cents] is always in `(-50, +50]`: a frequency exactly halfway between two notes is
 * reported as `+50 ¢` of the lower one, so the displayed note never flips at the midpoint
 * because of rounding noise.
 */
data class Pitch(val note: Note, val cents: Double) {
    val midi: Int get() = note.midi
}

fun nearestNote(hz: Double, a4Hz: Double = Tuning.DEFAULT_A4_HZ): Pitch {
    val midi = hzToMidi(hz, a4Hz)
    var nearest = midi.roundToInt()
    var cents = (midi - nearest) * 100.0
    if (cents <= -50.0) {
        nearest -= 1
        cents += 100.0
    }
    return Pitch(Note(nearest), cents)
}
