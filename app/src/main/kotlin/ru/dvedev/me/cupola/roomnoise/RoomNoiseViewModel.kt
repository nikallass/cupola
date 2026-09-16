package ru.dvedev.me.cupola.roomnoise

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.AppGraph
import ru.dvedev.me.cupola.analysis.SpectrumSnapshot
import ru.dvedev.me.cupola.audio.FrameListener
import ru.dvedev.me.cupola.dsp.metrics.RoomNoise
import ru.dvedev.me.cupola.dsp.metrics.RoomNoiseSession

enum class RoomNoiseStep { INTRO, MEASURING, RESULT }

data class RoomNoiseUiState(
    val step: RoomNoiseStep = RoomNoiseStep.INTRO,
    val progress: Float = 0f,
    val secondsLeft: Double = 0.0,
    /** Live level, dBFS, for the meter. */
    val levelDbfs: Double = -100.0,
    /** The microphone delivers nothing yet (digital zero): the countdown waits. */
    val noSignal: Boolean = false,
    val result: RoomNoise? = null,
    val saved: Boolean = false,
)

/**
 * Measures the room (owner decision 2026‑09‑15: no personal calibration — only a noise
 * profile that is subtracted from every spectrum) from the live engine and stores it.
 */
class RoomNoiseViewModel(private val graph: AppGraph) : ViewModel() {
    private val engine = graph.engine
    private val _state = MutableStateFlow(RoomNoiseUiState())
    val state: StateFlow<RoomNoiseUiState> = _state
    val spectrum = SpectrumSnapshot(hopSeconds = engine.analyzer.hopSeconds) { engine.analyzer.noise }

    var running: RoomNoiseSession? by mutableStateOf(null)
        private set

    private var tick = 0

    private val listener = FrameListener { m, spectrum ->
        val s = running
        if (s != null && !s.done) {
            s.push(spectrum.db, m.splDbfs)
            if (s.done) {
                _state.value = _state.value.copy(step = RoomNoiseStep.RESULT, progress = 1f, secondsLeft = 0.0, result = s.result())
                return@FrameListener
            }
        }
        if (++tick % 5 == 0) {
            _state.value = _state.value.copy(
                step = when { s == null -> _state.value.step; s.done -> RoomNoiseStep.RESULT; else -> RoomNoiseStep.MEASURING },
                progress = s?.progress?.toFloat() ?: 0f,
                secondsLeft = s?.secondsLeft ?: 0.0,
                levelDbfs = m.splDbfs,
                noSignal = m.splDbfs < RoomNoiseSession.DIGITAL_SILENCE_DBFS || engine.inputSilent.value,
            )
        }
    }

    init {
        engine.addListener(listener)
        engine.addListener(spectrum)
    }

    fun begin() {
        if (!engine.isRunning) engine.start(graph.settingsState.value.audioSource)
        val a = engine.analyzer
        running = RoomNoiseSession(a.hopSeconds, a.sampleRate, a.fftSize)
        _state.value = RoomNoiseUiState(step = RoomNoiseStep.MEASURING)
    }

    fun restart() {
        running = null
        _state.value = RoomNoiseUiState()
    }

    fun save() {
        val rn = _state.value.result ?: return
        viewModelScope.launch {
            graph.settings.saveRoomNoise(rn)
            _state.value = _state.value.copy(saved = true)
        }
    }

    override fun onCleared() {
        engine.removeListener(listener)
        engine.removeListener(spectrum)
    }
}
