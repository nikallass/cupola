package ru.dvedev.me.cupola.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.notation.Note
import kotlin.math.roundToInt

/** What the note zone shows: a note that is stable for the eye, not the raw 100 Hz estimate. */
data class DisplayNote(
    val voiced: Boolean = false,
    val note: Note = Note(69),
    /** Mean deviation over the window, cents. */
    val cents: Double = Double.NaN,
    /** Mean f0 over the window, Hz. */
    val f0Hz: Double = 0.0,
    /** True while the last note is held after voicing stopped. */
    val holding: Boolean = false,
    /** `RingRatio_norm` smoothed over ~300 ms of voiced frames; NaN without calibration or voice. */
    val ringNormDb: Double = Double.NaN,
    /** Share of energy in the cupola band, %, smoothed the same way. */
    val ringSharePct: Double = Double.NaN,
    /** Audible overtones, typical value over the window. */
    val overtones: Int = 0,
    /** True when the ring was being counted (all gates open) for most of the window. */
    val counted: Boolean = false,
)

/**
 * Smooths the note readout (owner feedback 2026‑09‑15: «нота дёргается»): over the last
 * [windowSeconds] the most frequent note wins, cents and f0 are averaged over the frames
 * of that note; when voicing drops the last value is held for [holdSeconds] before «—».
 * Publishes at [publishHz] so the text does not recompose faster than the eye reads.
 */
class NoteDisplaySmoother(
    private val hopSeconds: Double,
    windowSeconds: Double = 0.4,
    private val holdSeconds: Double = 0.6,
    private val minVoicedShare: Double = 0.3,
    /** A frame counts as a sung note only with this many audible harmonics (a lone orchestral tone has one). */
    private val minAudibleHarmonics: Int = 3,
    /** A different note must be the winner for this long before the display switches (hysteresis). */
    private val switchSeconds: Double = 0.2,
    publishHz: Int = 20,
) : FrameListener {
    private val size = (windowSeconds / hopSeconds).roundToInt().coerceAtLeast(4)
    private val midi = IntArray(size) { -1 }
    private val cents = DoubleArray(size)
    private val f0 = DoubleArray(size)
    private var head = 0
    private var filled = 0
    private var lastVoicedAt = Double.NEGATIVE_INFINITY
    private var frames = 0
    private val publishEvery = (100.0 / publishHz).roundToInt().coerceAtLeast(1)
    private val counts = HashMap<Int, Int>()

    private val _state = MutableStateFlow(DisplayNote())
    val state: StateFlow<DisplayNote> = _state.asStateFlow()
    private var candidate = -1
    private var candidateSince = 0.0
    private var ringEma = Double.NaN
    private var shareEma = Double.NaN
    private var overtoneSum = 0
    private var overtoneN = 0
    private var openFrames = 0
    private var voiceFrames = 0
    private val emaAlpha = 1.0 - kotlin.math.exp(-hopSeconds / 0.3)

    override fun onFrame(metrics: FrameMetrics, spectrum: PowerSpectrum) {
        val trusted = metrics.voiced && metrics.harmonics.count { it.audible } >= minAudibleHarmonics
        midi[head] = if (trusted) metrics.note.midi else -1
        cents[head] = metrics.cents
        f0[head] = metrics.f0Hz
        head = (head + 1) % size
        if (filled < size) filled++
        if (trusted) lastVoicedAt = metrics.timeSec
        if (metrics.voice) {
            voiceFrames++
            if (metrics.gate == ru.dvedev.me.cupola.dsp.score.Gate.OPEN) openFrames++
            val r = metrics.ringRatioNorm
            if (!r.isNaN()) ringEma = if (ringEma.isNaN()) r else ringEma + (r - ringEma) * emaAlpha
            shareEma = if (shareEma.isNaN()) metrics.ringSharePct else shareEma + (metrics.ringSharePct - shareEma) * emaAlpha
        }
        if (trusted) { overtoneSum += metrics.overtoneCount; overtoneN++ }
        if (++frames % publishEvery != 0) return
        val counted = voiceFrames > 0 && openFrames * 2 >= voiceFrames
        val overtones = if (overtoneN > 0) (overtoneSum.toDouble() / overtoneN).roundToInt() else 0
        voiceFrames = 0; openFrames = 0; overtoneSum = 0; overtoneN = 0

        counts.clear()
        for (i in 0 until filled) {
            val m = midi[i]
            if (m >= 0) counts[m] = (counts[m] ?: 0) + 1
        }
        val voicedFrames = counts.values.sum()
        val previous = _state.value
        if (voicedFrames >= minVoicedShare * size) {
            var best = counts.maxByOrNull { it.value }!!.key
            // hysteresis: keep the shown note while it still has support, unless the
            // challenger has been winning for switchSeconds
            val shown = if (previous.voiced) previous.note.midi else -1
            if (shown >= 0 && best != shown && (counts[shown] ?: 0) > 0) {
                if (candidate != best) { candidate = best; candidateSince = metrics.timeSec }
                if (metrics.timeSec - candidateSince < switchSeconds) best = shown
            } else {
                candidate = -1
            }
            var sumC = 0.0
            var sumF = 0.0
            var n = 0
            for (i in 0 until filled) {
                if (midi[i] == best) { sumC += cents[i]; sumF += f0[i]; n++ }
            }
            _state.value = DisplayNote(voiced = true, note = Note(best), cents = sumC / n, f0Hz = sumF / n, holding = false, ringNormDb = ringEma, ringSharePct = shareEma, overtones = overtones, counted = counted)
        } else if (previous.voiced && metrics.timeSec - lastVoicedAt < holdSeconds) {
            _state.value = previous.copy(holding = true, ringNormDb = ringEma, ringSharePct = shareEma, counted = counted)
        } else {
            _state.value = DisplayNote(ringNormDb = if (metrics.voice) ringEma else Double.NaN, ringSharePct = if (metrics.voice) shareEma else Double.NaN, counted = counted)
        }
    }

    fun reset() {
        midi.fill(-1)
        filled = 0
        head = 0
        _state.value = DisplayNote()
    }
}
