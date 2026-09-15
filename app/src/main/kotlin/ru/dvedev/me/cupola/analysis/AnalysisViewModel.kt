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
import ru.dvedev.me.cupola.audio.AudioEngine
import ru.dvedev.me.cupola.audio.AudioSourcePreference
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.dsp.session.SessionSummary
import ru.dvedev.me.cupola.notation.Note

/**
 * State holder of the Analysis screen. Analysis runs whenever the app is visible
 * (SPEC §15.5); a session (points, hints) is opened with «Старт».
 */
@OptIn(FlowPreview::class)
class AnalysisViewModel(private val engine: AudioEngine) : ViewModel() {
    val engineState: StateFlow<EngineState> = engine.state

    /** Text readouts recompose at ~25 Hz; canvases pull from the histories every display frame. */
    val uiMetrics: StateFlow<FrameMetrics?> = engine.metrics.sample(40).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val spectrogram = SpectrogramHistory(hopSeconds = engine.analyzer.hopSeconds)
    val spectrum = SpectrumSnapshot { engine.analyzer.noise }
    val session = SessionController(engine.analyzer.hopSeconds)

    /** Pinned target note (tap on the note zone), or null. */
    var targetNote: Note? by mutableStateOf(null)
        private set

    var voiceType: VoiceType by mutableStateOf(VoiceType.UNSET)
        private set

    var sourcePreference: AudioSourcePreference = AudioSourcePreference.AUTO

    init {
        engine.addListener(spectrogram)
        engine.addListener(spectrum)
        engine.addListener(session)
    }

    fun startListening() {
        engine.start(sourcePreference)
    }

    fun stopListening() {
        engine.stop()
    }

    fun toggleTarget(current: Note?) {
        targetNote = if (targetNote == null) current else null
    }

    fun startSession() = session.start()

    fun stopSession(): SessionSummary? = session.stop()

    fun togglePause() = session.togglePause()

    fun dismissSummary() = session.dismissSummary()

    fun setVoice(type: VoiceType) {
        voiceType = type
        engine.updateConfig { it.copy(band = type.band) }
    }

    val calibrationBaselineDb: Double? get() = engine.config.calibration?.ringRatioDb
    val overruns: Long get() = engine.overruns
    val processingLatencyMs: Double get() = engine.processingLatencyMs

    override fun onCleared() {
        engine.removeListener(spectrogram)
        engine.removeListener(spectrum)
        engine.removeListener(session)
    }
}
