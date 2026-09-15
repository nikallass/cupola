package ru.dvedev.me.cupola.calibration

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
import ru.dvedev.me.cupola.dsp.calibration.CalibrationPhase
import ru.dvedev.me.cupola.dsp.calibration.CalibrationResult
import ru.dvedev.me.cupola.dsp.calibration.CalibrationSession

enum class CalibrationStep { INTRO, SILENCE, VOWEL, RESULT }

data class CalibrationUiState(
    val step: CalibrationStep = CalibrationStep.INTRO,
    val progress: Float = 0f,
    val secondsLeft: Double = 0.0,
    /** Live level, dBFS, for the meter. */
    val levelDbfs: Double = -100.0,
    val voiced: Boolean = false,
    val result: CalibrationResult? = null,
    val saved: Boolean = false,
)

/** Drives [CalibrationSession] from the live engine and stores the result (T-061). */
class CalibrationViewModel(private val graph: AppGraph) : ViewModel() {
    private val engine = graph.engine
    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state
    val spectrum = SpectrumSnapshot { engine.analyzer.noise }

    var running: CalibrationSession? by mutableStateOf(null)
        private set

    private var tick = 0

    private val listener = FrameListener { m, _ ->
        val s = running
        if (s != null && s.phase != CalibrationPhase.DONE) {
            s.push(CalibrationSession.Input(m.splDbfs, m.ringRatioDb, m.splDbfs, m.confidence, m.voice))
            if (s.phase == CalibrationPhase.DONE) {
                val result = s.result()
                _state.value = _state.value.copy(step = CalibrationStep.RESULT, progress = 1f, secondsLeft = 0.0, result = result)
                return@FrameListener
            }
        }
        if (++tick % 5 == 0) {
            _state.value = _state.value.copy(
                step = when (s?.phase) {
                    CalibrationPhase.SILENCE -> CalibrationStep.SILENCE
                    CalibrationPhase.VOWEL -> CalibrationStep.VOWEL
                    CalibrationPhase.DONE -> CalibrationStep.RESULT
                    null -> _state.value.step
                },
                progress = s?.progress?.toFloat() ?: 0f,
                secondsLeft = s?.secondsLeft ?: 0.0,
                levelDbfs = m.splDbfs,
                voiced = m.voiced,
            )
        }
    }

    init {
        engine.addListener(listener)
        engine.addListener(spectrum)
    }

    fun begin() {
        val s = graph.settingsState.value
        running = CalibrationSession(engine.analyzer.hopSeconds, s.band, confidenceMin = 0.5) // the baseline needs voice, not a precise pitch
        _state.value = CalibrationUiState(step = CalibrationStep.SILENCE)
    }

    fun restart() {
        running = null
        _state.value = CalibrationUiState()
    }

    fun save() {
        val cal = _state.value.result?.calibration ?: return
        val key = graph.settingsState.value.calibrationKey
        viewModelScope.launch {
            graph.settings.saveCalibration(key, cal)
            _state.value = _state.value.copy(saved = true)
        }
    }

    override fun onCleared() {
        engine.removeListener(listener)
        engine.removeListener(spectrum)
    }
}
