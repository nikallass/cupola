package ru.dvedev.me.cupola.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.notation.Note
import kotlin.math.abs
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
    /** The cupola indicator 0..1 (scorer's attack/release-smoothed `ring`); 0 without voice. */
    val ring: Double = 0.0,
    /** Share of the voice energy in the cupola band, %, smoothed over ~300 ms; NaN without voice. */
    val ringSharePct: Double = Double.NaN,
    /** Band hump over its flanks, dB, smoothed the same way; NaN without voice. */
    val humpDb: Double = Double.NaN,
    /** Audible overtones, typical value over the window. */
    val overtones: Int = 0,
    /** True when the ring was being counted (all gates open) for most of the window. */
    val counted: Boolean = false,
    /** The gate that blocked the ring most often in the window (OPEN when counted). */
    val gate: ru.dvedev.me.cupola.dsp.score.Gate = ru.dvedev.me.cupola.dsp.score.Gate.OPEN,
)

/**
 * Smooths the note readout (owner feedback 2026‑09‑15: «нота дёргается»): over the last
 * [windowSeconds] the most frequent note wins, cents and f0 are averaged over the frames
 * of that note; when voicing drops the last value is held for [holdSeconds] before «—».
 * Publishes at [publishHz]; the published cents and Hz are averaged over the last
 * [averagingMs] (settings) so the pin glides instead of twitching.
 */
class NoteDisplaySmoother(
    private val hopSeconds: Double,
    windowSeconds: Double = 0.4,
    private val holdSeconds: Double = 0.6,
    private val minVoicedShare: Double = 0.3,
    /** A frame counts as a sung note only with this many audible harmonics (a lone orchestral tone has one). */
    private val minAudibleHarmonics: Int = 3,
    /** A different note must be the winner for this long before the display switches (hysteresis). */
    private val switchSeconds: Double = 0.3,
    /** When no single note holds this share of the voiced frames the window is "chaotic": keep the shown note. */
    private val chaosShare: Double = 0.5,
    publishHz: Int = 50,
    /** Boxcar over the last N ms of published cents/Hz (owner 2026‑09‑16: the pin twitched); 0 = off. */
    private val averagingMs: () -> Int = { 100 },
) : FrameListener {
    private val avgTime = DoubleArray(64)
    private val avgNote = IntArray(64)
    private val avgCents = DoubleArray(64)
    private val avgF0 = DoubleArray(64)
    private var avgHead = 0
    private var avgCount = 0
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
    private var humpEma = Double.NaN
    private var shareEma = Double.NaN
    private var ring = 0.0
    private var overtoneSum = 0
    private var overtoneN = 0
    private var openFrames = 0
    private var voiceFrames = 0
    private val gateCounts = IntArray(ru.dvedev.me.cupola.dsp.score.Gate.entries.size)
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
            if (metrics.gate == ru.dvedev.me.cupola.dsp.score.Gate.OPEN) openFrames++ else gateCounts[metrics.gate.ordinal]++
            val h = metrics.humpDb
            if (!h.isNaN()) humpEma = if (humpEma.isNaN()) h else humpEma + (h - humpEma) * emaAlpha
            shareEma = if (shareEma.isNaN()) metrics.ringSharePct else shareEma + (metrics.ringSharePct - shareEma) * emaAlpha
        }
        ring = metrics.ring
        if (trusted) { overtoneSum += metrics.overtoneCount; overtoneN++ }
        if (++frames % publishEvery != 0) return
        val counted = voiceFrames > 0 && openFrames * 2 >= voiceFrames
        var blocking = ru.dvedev.me.cupola.dsp.score.Gate.OPEN
        if (!counted) {
            var bestN = 0
            for (g in ru.dvedev.me.cupola.dsp.score.Gate.entries) if (gateCounts[g.ordinal] > bestN) { bestN = gateCounts[g.ordinal]; blocking = g }
        }
        gateCounts.fill(0)
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
            // the note is derived from the MEAN continuous pitch of the window, not from the
            // per-frame mode: a vibrato that straddles a note boundary then sits on its centre
            // instead of flipping left and right. Frames more than a semitone away from the
            // modal note (detector slips) are left out of the mean.
            var modal = counts.maxByOrNull { it.value }!!.key
            val shownNote = if (previous.voiced) previous.note.midi else -1
            // owner rule: a confidently shown note survives a burst of octave-hopping estimates —
            // when no note dominates the window, average around the shown note instead
            val chaotic = counts.getValue(modal) < chaosShare * voicedFrames
            if (chaotic && shownNote >= 0) modal = shownNote
            var sumP = 0.0
            var sumF = 0.0
            var n = 0
            for (i in 0 until filled) {
                if (midi[i] < 0) continue
                val p = midi[i] + cents[i] / 100.0
                if (abs(p - modal) <= 1.0) { sumP += p; sumF += f0[i]; n++ }
            }
            if (n == 0 || (chaotic && shownNote >= 0 && n < minVoicedShare * size)) {
                if (previous.voiced && !previous.holding) _state.value = previous.copy(holding = true)
                return
            }
            val meanP = sumP / n
            var best = meanP.roundToInt()
            // hysteresis: keep the shown note while it still has support, unless the
            // challenger has been winning for switchSeconds
            val shown = if (previous.voiced) previous.note.midi else -1
            if (shown >= 0 && best != shown) {
                if (candidate != best) { candidate = best; candidateSince = metrics.timeSec }
                if (metrics.timeSec - candidateSince < switchSeconds || chaotic) {
                    // keep the shown note; if the mean has drifted past a semitone from it, hold the old readout untouched
                    if (abs(meanP - shown) >= 0.75) {
                        if (!previous.holding) _state.value = previous.copy(holding = true)
                        return
                    }
                    best = shown
                }
            } else {
                candidate = -1
            }
            val centsNow = ((meanP - best) * 100.0).coerceIn(-75.0, 75.0)
            val f0Now = sumF / n
            // average the published values of the same note over the last averagingMs
            avgTime[avgHead] = metrics.timeSec; avgNote[avgHead] = best; avgCents[avgHead] = centsNow; avgF0[avgHead] = f0Now
            avgHead = (avgHead + 1) % avgTime.size
            if (avgCount < avgTime.size) avgCount++
            val span = averagingMs() / 1000.0
            var cSum = 0.0
            var fSum = 0.0
            var cnt = 0
            for (i in 0 until avgCount) {
                if (avgNote[i] != best || metrics.timeSec - avgTime[i] > span + 1e-6) continue
                cSum += avgCents[i]; fSum += avgF0[i]; cnt++
            }
            val centsOut = if (cnt > 0) cSum / cnt else centsNow
            val f0Out = if (cnt > 0) fSum / cnt else f0Now
            _state.value = DisplayNote(voiced = true, note = Note(best), cents = centsOut, f0Hz = f0Out, holding = false, ring = ring, ringSharePct = shareEma, humpDb = humpEma, overtones = overtones, counted = counted, gate = blocking)
        } else if (previous.voiced && metrics.timeSec - lastVoicedAt < holdSeconds) {
            _state.value = previous.copy(holding = true, ring = ring, ringSharePct = shareEma, humpDb = humpEma, counted = counted, gate = blocking)
        } else {
            _state.value = DisplayNote(ring = ring, ringSharePct = if (metrics.voice) shareEma else Double.NaN, humpDb = if (metrics.voice) humpEma else Double.NaN, counted = counted, gate = blocking)
        }
    }

    fun reset() {
        midi.fill(-1)
        filled = 0
        head = 0
        _state.value = DisplayNote()
    }
}
