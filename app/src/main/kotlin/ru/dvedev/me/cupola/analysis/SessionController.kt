package ru.dvedev.me.cupola.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.session.SessionAccumulator
import ru.dvedev.me.cupola.dsp.session.SessionSummary

/** Which hint is on screen (SPEC §7a, v0.1 subset). Order = priority. */
enum class HintKey { DRIFT, GOOD }

/** One award of points, for the flying-dots animation. [id] increases monotonically. */
data class PointsEvent(val id: Long, val portion: Int, val ring: Double, val green: Boolean)

data class SessionUiState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedSec: Double = 0.0,
    val points: Int = 0,
    val lastPoints: PointsEvent? = null,
    val hint: HintKey? = null,
    val streakSeconds: Double = 0.0,
    /** Summary of the session that just ended, until dismissed. */
    val summary: SessionSummary? = null,
)

/**
 * Session layer over the live analysis (SPEC §15.5, T-055): «Старт» opens a session —
 * points, streaks and hints; «Стоп» closes it with a summary. Pause freezes the clock
 * and the points; analysis itself never stops.
 */
class SessionController(private val hopSeconds: Double) : FrameListener {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    @Volatile private var accumulator: SessionAccumulator? = null
    @Volatile private var paused = false
    private var elapsed = 0.0
    private var eventId = 0L
    private var lastHintAt = Double.NEGATIVE_INFINITY
    private var hintShownAt = Double.NEGATIVE_INFINITY
    private var hint: HintKey? = null
    private var pendingPoints: PointsEvent? = null
    private var dirty = 0

    val isActive: Boolean get() = accumulator != null

    fun start() {
        accumulator = SessionAccumulator(hopSeconds)
        paused = false
        elapsed = 0.0
        hint = null
        lastHintAt = Double.NEGATIVE_INFINITY
        _state.value = SessionUiState(active = true)
    }

    val isPaused: Boolean get() = paused

    fun stop(): SessionSummary? {
        val acc = accumulator ?: return null
        accumulator = null
        val summary = acc.summary()
        _state.value = SessionUiState(active = false, paused = paused, summary = summary, points = summary.points, elapsedSec = summary.durationSec)
        return summary
    }

    fun togglePause() {
        paused = !paused
        _state.value = _state.value.copy(paused = paused)
    }

    fun dismissSummary() {
        _state.value = _state.value.copy(summary = null)
    }

    override fun onFrame(metrics: FrameMetrics, spectrum: PowerSpectrum) {
        val acc = accumulator ?: return
        if (paused) return
        elapsed += hopSeconds
        val awarded = acc.add(metrics)
        if (awarded > 0) {
            pendingPoints = PointsEvent(++eventId, awarded, metrics.ring, metrics.score >= GREEN_SCORE)
        }
        updateHint(metrics)
        // publish at ~20 Hz, or immediately when something discrete happened
        if (++dirty >= PUBLISH_EVERY || awarded > 0) {
            dirty = 0
            _state.value = SessionUiState(
                active = true,
                paused = false,
                elapsedSec = elapsed,
                points = acc.points.total,
                lastPoints = pendingPoints,
                hint = hint,
                streakSeconds = metrics.streakSeconds,
            )
        }
    }

    private fun updateHint(m: FrameMetrics) {
        val now = elapsed
        // current hint expires after HINT_SECONDS
        if (hint != null && now - hintShownAt >= HINT_SECONDS) hint = null
        if (now - lastHintAt < HINT_SECONDS) return
        val candidate = when {
            !m.voice -> null
            m.voiced && !m.pitchSd.isNaN() && m.pitchSd > DRIFT_SD_CENTS -> HintKey.DRIFT
            m.streakSeconds >= 3.0 -> HintKey.GOOD
            else -> null
        }
        if (candidate != null && candidate != hint) {
            hint = candidate
            hintShownAt = now
            lastHintAt = now
        }
    }

    companion object {
        const val GREEN_SCORE = 0.85
        /** Median-line pitch SD above which «Нота плывёт» is offered (SPEC §15.5). */
        const val DRIFT_SD_CENTS = 20.0
        const val HINT_SECONDS = 3.0
        private const val PUBLISH_EVERY = 5
    }
}
