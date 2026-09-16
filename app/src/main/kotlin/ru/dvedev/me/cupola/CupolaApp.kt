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
import ru.dvedev.me.cupola.dsp.metrics.RoomNoise
import ru.dvedev.me.cupola.dsp.session.SessionSummary
import ru.dvedev.me.cupola.haptics.HapticsController
import ru.dvedev.me.cupola.service.AnalysisService
import ru.dvedev.me.cupola.settings.Settings
import ru.dvedev.me.cupola.settings.SettingsRepository

/**
 * Manual dependency graph (SPEC §15.2: no Hilt). One instance per process, reachable
 * through [Context.appGraph]; screens get what they need from here via their ViewModels.
 */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val engine = AudioEngine(app, AnalyzerConfig())
    val session = SessionController(engine.analyzer.hopSeconds)
    val haptics = HapticsController(app, scope)

    /** True while the Activity is started; the microphone stops in the background without a session. */
    @Volatile var activityVisible: Boolean = false

    private val isTablet: Boolean = isTabletDevice(app)

    val settingsState: StateFlow<Settings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    /** The measured room noise profile, or null (the floor then adapts on its own). */
    val roomNoiseState: StateFlow<RoomNoise?> = settings.roomNoise.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        engine.addListener(session)
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) installPitchTrace()
        // keep the analyzer in sync with settings and the room profile
        scope.launch {
            combine(settings.settings, roomNoiseState) { s, rn -> s to rn }.collect { (s, rn) ->
                val restart = engine.config.fftSize != s.fftSize
                engine.updateConfig {
                    it.copy(
                        band = s.band,
                        a4Hz = s.effectiveA4Hz,
                        confidenceMin = s.confidenceMin,
                        fftSize = s.fftSize, // hop stays 480 (10 ms) for both 2048 and 4096 (SPEC §4)
                        scoreParams = s.scoreParams(),
                        vibratoThresholds = s.vibratoThresholds(),
                    )
                }
                if (restart && engine.isRunning) {
                    engine.stop()
                    engine.start(s.audioSource)
                }
                val a = engine.analyzer
                if (rn != null) a.pendingRoomNoise = rn.resampled(a.sampleRate, a.fftSize)
            }
        }
    }

    /** Debug builds: every 10th voiced frame of the pitch tracker goes to logcat (`CupolaTrace`). */
    private fun installPitchTrace() {
        var n = 0
        engine.pitchTrace = { raw, out ->
            if (raw.found && ++n % 10 == 0) {
                val t = engine.analyzer.pitchTracker
                val sb = StringBuilder("yin %6.1f/%.2f -> %6.1f/%.2f trk %6.1f/%2.0f %-5s r%d |".format(raw.f0Hz, raw.confidence, out.f0Hz, out.confidence, t.trackedHz, t.trackStrength, t.lastDecision, t.yinReject))
                for (i in 0 until t.candidateCount) sb.append(" %.0f:%.0f/%d".format(t.candidates[i].hz, t.candidates[i].score, t.candidates[i].count))
                android.util.Log.d("CupolaTrace", sb.toString())
            }
        }
    }

    /** Effective haptics switch: explicit setting, else on for phones and off for tablets (SPEC §15.5). */
    fun hapticsEnabled(): Boolean = settingsState.value.haptics ?: !isTablet

    /** Opens a session: points, hints, foreground service, haptics. */
    fun startSession() {
        if (session.isActive) return
        session.start()
        AnalysisService.start(app)
        haptics.start(
            ring = { engine.metrics.value?.ring ?: 0.0 },
            enabled = { session.isActive && !session.state.value.paused && hapticsEnabled() },
        )
    }

    /** Closes the session (from the screen or the notification) and returns its summary. */
    fun stopSession(): SessionSummary? {
        val summary = session.stop()
        android.util.Log.i("CupolaAudio", "session stopped: overruns=${engine.overruns} readErrors=${engine.readErrors} latency=${"%.1f".format(engine.processingLatencyMs)}ms points=${summary?.points}")
        haptics.stop()
        AnalysisService.stop(app)
        if (!activityVisible) engine.stop()
        return summary
    }
}

/**
 * Tablet = `smallestScreenWidthDp ≥ 600` or a physical diagonal ≥ 7". The KENSHI E11 reports
 * only 500 dp at 240 dpi although it is a 10" device, so the diagonal check matters.
 */
fun isTabletDevice(context: Context): Boolean {
    val cfg = context.resources.configuration
    if (cfg.smallestScreenWidthDp >= 600) return true
    val dm = context.resources.displayMetrics
    if (dm.xdpi <= 0f || dm.ydpi <= 0f) return false
    val w = dm.widthPixels / dm.xdpi
    val h = dm.heightPixels / dm.ydpi
    return kotlin.math.sqrt((w * w + h * h).toDouble()) >= 7.0
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
