package ru.dvedev.me.cupola.analysis

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import ru.dvedev.me.cupola.audio.AudioEngine
import ru.dvedev.me.cupola.audio.AudioSourcePreference
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.dsp.FrameMetrics

/**
 * State holder of the Analysis screen. Analysis runs whenever the app is visible
 * (SPEC §15.5); the session layer (start/stop, points, hints) lands with T-055.
 */
class AnalysisViewModel(private val engine: AudioEngine) : ViewModel() {
    val metrics: StateFlow<FrameMetrics?> = engine.metrics
    val engineState: StateFlow<EngineState> = engine.state

    var sourcePreference: AudioSourcePreference = AudioSourcePreference.AUTO

    /** Called when the screen becomes visible and the microphone permission is granted. */
    fun startListening() {
        engine.start(sourcePreference)
    }

    /** Called when the screen is hidden without an active session. */
    fun stopListening() {
        engine.stop()
    }

    val overruns: Long get() = engine.overruns
    val readErrors: Long get() = engine.readErrors
    val processingLatencyMs: Double get() = engine.processingLatencyMs
}
