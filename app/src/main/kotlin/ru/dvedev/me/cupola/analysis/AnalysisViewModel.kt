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
import ru.dvedev.me.cupola.AppGraph
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.calibration.Calibration
import ru.dvedev.me.cupola.dsp.session.SessionSummary
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.settings.Settings

/**
 * State holder of the Analysis screen. Analysis runs whenever the app is visible
 * (SPEC §15.5); a session (points, hints) is opened with «Старт» and needs a calibration.
 */
@OptIn(FlowPreview::class)
class AnalysisViewModel(private val graph: AppGraph) : ViewModel() {
    private val engine = graph.engine
    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<Settings> = graph.settingsState
    val calibration: StateFlow<Calibration?> = graph.calibrationState
    val session: SessionController = graph.session

    /** Text readouts recompose at ~25 Hz; canvases pull from the histories every display frame. */
    val uiMetrics: StateFlow<FrameMetrics?> = engine.metrics.sample(40).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val spectrogram = SpectrogramHistory(hopSeconds = engine.analyzer.hopSeconds)
    val spectrum = SpectrumSnapshot { engine.analyzer.noise }

    /** Pinned target note (tap on the note zone), or null. */
    var targetNote: Note? by mutableStateOf(null)
        private set

    /** «Старт» pressed without a calibration: the screen offers to calibrate. */
    var calibrationPrompt: Boolean by mutableStateOf(false)
        private set

    init {
        engine.addListener(spectrogram)
        engine.addListener(spectrum)
    }

    fun startListening() {
        engine.start(settings.value.audioSource)
    }

    /** Called on ON_STOP; the microphone keeps running only inside a session (T-042). */
    fun stopListeningIfIdle() {
        if (!session.isActive) engine.stop()
    }

    fun toggleTarget(current: Note?) {
        targetNote = if (targetNote == null) current else null
    }

    /** Returns false when a calibration is required first. */
    fun startSession(): Boolean {
        if (calibration.value == null) {
            calibrationPrompt = true
            return false
        }
        session.start()
        return true
    }

    fun dismissCalibrationPrompt() {
        calibrationPrompt = false
    }

    fun stopSession(): SessionSummary? = session.stop()

    fun togglePause() = session.togglePause()

    fun dismissSummary() = session.dismissSummary()

    val overruns: Long get() = engine.overruns
    val processingLatencyMs: Double get() = engine.processingLatencyMs

    override fun onCleared() {
        engine.removeListener(spectrogram)
        engine.removeListener(spectrum)
    }
}
