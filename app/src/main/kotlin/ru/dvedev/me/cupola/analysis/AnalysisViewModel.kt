package ru.dvedev.me.cupola.analysis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.AppGraph
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.session.SessionSummary
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.settings.Settings

/**
 * State holder of the Analysis screen. Analysis runs whenever the app is visible
 * (SPEC §15.5); a session (points, hints) is opened with «Старт».
 */
@OptIn(FlowPreview::class)
class AnalysisViewModel(private val graph: AppGraph) : ViewModel() {
    private val engine = graph.engine
    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<Settings> = graph.settingsState
    val session: SessionController = graph.session

    /** Text readouts recompose at ~25 Hz; canvases pull from the histories every display frame. */
    val uiMetrics: StateFlow<FrameMetrics?> = engine.metrics.sample(40).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val spectrogram = SpectrogramHistory(hopSeconds = engine.analyzer.hopSeconds)
    val spectrum = SpectrumSnapshot(hopSeconds = engine.analyzer.hopSeconds) { engine.analyzer.noise }
    val noteSmoother = NoteDisplaySmoother(engine.analyzer.hopSeconds, averagingMs = { graph.settingsState.value.displayAveragingMs })
    val displayNote: StateFlow<DisplayNote> = noteSmoother.state


    /** Pinned target note (tap on the note zone), or null. */
    var targetNote: Note? by mutableStateOf(null)
        private set

    /** Pause (T-056): readouts freeze on the last frame, the spectrogram can be scrolled back. */
    var frozenMetrics: FrameMetrics? by mutableStateOf(null)
        private set
    var frozenNote: DisplayNote? by mutableStateOf(null)
        private set
    var pausedHead: Long? by mutableStateOf(null)
        private set
    var scrollColumns: Int by mutableStateOf(0)
        private set

    val paused: Boolean get() = pausedHead != null

    /** Last column of the spectrogram window: live (null) or the paused position minus the scroll. */
    val viewEnd: Long? get() = pausedHead?.let { (it - scrollColumns).coerceAtLeast(0) }

    /** Visible spectrogram time span in columns (10 ms each): 2…30 s, pinch on the spectrogram. */
    private var spanExact = 800f
    var spectrogramSpan: Int by mutableStateOf(800)
        private set

    fun zoomSpectrogram(factor: Float) {
        if (factor <= 0f) return
        // exact float span: per-event pinch factors are ~1.01, integer truncation would eat them
        spanExact = (spanExact / factor).coerceIn(200f, 3000f)
        spectrogramSpan = spanExact.toInt()
        scrollBy(0)
    }

    fun scrollBy(columns: Int) {
        val head = pausedHead ?: return
        val maxBack = (minOf(head, spectrogram.columns.toLong()) - spectrogramSpan).coerceAtLeast(0L).toInt()
        scrollColumns = (scrollColumns + columns).coerceIn(0, maxBack)
    }

    init {
        engine.addListener(spectrogram)
        engine.addListener(spectrum)
        engine.addListener(noteSmoother)
    }

    fun startListening() {
        if (engine.start(settings.value.audioSource)) return
        // the microphone did not open (busy, or the runtime grant not yet effective): retry
        // every couple of seconds while the screen is visible instead of staying dark
        viewModelScope.launch {
            var attempts = 0
            while (graph.activityVisible && attempts++ < 10 && !engine.isRunning) {
                kotlinx.coroutines.delay(2000)
                if (graph.activityVisible && engine.start(settings.value.audioSource)) break
            }
        }
    }

    /** The microphone delivers silence (busy or silenced by the system); the note zone says so. */
    val inputSilent: StateFlow<Boolean> = engine.inputSilent

    /** Called on ON_STOP; the microphone keeps running only inside a session (T-042). */
    fun stopListeningIfIdle() {
        if (!session.isActive) engine.stop()
    }

    fun toggleTarget(current: Note?) {
        targetNote = if (targetNote == null) current else null
        if (targetNote == null) referenceTone.stop()
    }

    /** Pins [note] as the target (note picker). */
    fun pinTarget(note: Note) {
        targetNote = note
    }

    private val referenceTone = ru.dvedev.me.cupola.audio.ReferenceTone()

    /** «Дать тон»: plays the pinned note at the configured A4. */
    fun playTargetTone() {
        val note = targetNote ?: return
        referenceTone.play(ru.dvedev.me.cupola.notation.midiToHz(note.midi, settings.value.effectiveA4Hz))
    }

    fun startSession() = graph.startSession()

    fun stopSession(): SessionSummary? = graph.stopSession()

    fun togglePause() {
        if (pausedHead == null) {
            frozenMetrics = uiMetrics.value
            frozenNote = displayNote.value
            pausedHead = spectrogram.head
            scrollColumns = 0
            spectrum.freeze()
        } else {
            frozenMetrics = null
            frozenNote = null
            pausedHead = null
            scrollColumns = 0
            spectrum.unfreeze()
        }
        session.togglePause()
    }

    fun dismissSummary() = session.dismissSummary()

    val overruns: Long get() = engine.overruns
    val processingLatencyMs: Double get() = engine.processingLatencyMs

    override fun onCleared() {
        referenceTone.stop()
        engine.removeListener(spectrogram)
        engine.removeListener(spectrum)
        engine.removeListener(noteSmoother)
    }
}
