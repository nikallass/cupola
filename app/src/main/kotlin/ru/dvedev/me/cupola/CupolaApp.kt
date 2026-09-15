package ru.dvedev.me.cupola

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.analysis.SessionController
import ru.dvedev.me.cupola.audio.AudioEngine
import ru.dvedev.me.cupola.dsp.AnalyzerConfig
import ru.dvedev.me.cupola.dsp.calibration.Calibration
import ru.dvedev.me.cupola.settings.Settings
import ru.dvedev.me.cupola.settings.SettingsRepository

/**
 * Manual dependency graph (SPEC §15.2: no Hilt). One instance per process, reachable
 * through [Context.appGraph]; screens get what they need from here via their ViewModels.
 */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val engine = AudioEngine(app, AnalyzerConfig())
    val session = SessionController(engine.analyzer.hopSeconds)

    /** Settings + the calibration for the current voice/band, or null. */
    val settingsState: StateFlow<Settings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    val calibrationState: StateFlow<Calibration?> = combine(settings.settings, settings.calibrations) { s, cals -> cals[s.calibrationKey] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        engine.addListener(session)
        // keep the analyzer in sync with settings and the current calibration
        scope.launch {
            combine(settings.settings, calibrationState) { s, cal -> s to cal }.collect { (s, cal) ->
                val restart = engine.config.fftSize != s.fftSize
                engine.updateConfig {
                    it.copy(
                        band = s.band,
                        calibration = cal,
                        a4Hz = s.a4Hz.toDouble(),
                        confidenceMin = s.confidenceMin,
                        splNormalisationK = s.splK,
                        fftSize = s.fftSize, // hop stays 480 (10 ms) for both 2048 and 4096 (SPEC §4)
                        scoreParams = s.scoreParams(),
                    )
                }
                if (restart && engine.isRunning) {
                    engine.stop()
                    engine.start(s.audioSource)
                }
            }
        }
    }
}

class CupolaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as CupolaApp).graph
