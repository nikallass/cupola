package ru.dvedev.me.cupola.audio

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.cupola.dsp.Analyzer
import ru.dvedev.me.cupola.dsp.AnalyzerConfig
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.frame.FloatRingBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Called on the analysis thread once per hop, with the live spectrum of that frame. */
fun interface FrameListener {
    fun onFrame(metrics: FrameMetrics, spectrum: PowerSpectrum)
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Running(val source: AudioSourceStatus) : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Microphone → [FloatRingBuffer] → [Analyzer] → [metrics] (SPEC §4, T-041).
 *
 * Two threads: the capture thread (`THREAD_PRIORITY_URGENT_AUDIO`) only reads
 * `AudioRecord` into the ring buffer; the analysis thread drains it hop by hop and runs
 * the DSP. If analysis falls behind, the ring buffer drops samples and [overruns] grows;
 * `AudioRecord` underruns show up as [readErrors].
 */
class AudioEngine(
    context: Context,
    config: AnalyzerConfig = AnalyzerConfig(),
) {
    private val appContext = context.applicationContext
    private val capture = AudioCapture(appContext)
    private val listeners = CopyOnWriteArrayList<FrameListener>()
    private val running = AtomicBoolean(false)
    private val samplesReady = Semaphore(0)
    private var captureThread: Thread? = null
    private var analysisThread: Thread? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** True while the microphone delivers digital silence or the client is silenced by policy (see the watchdog in [start]). */
    private val _inputSilent = MutableStateFlow(false)
    val inputSilent: StateFlow<Boolean> = _inputSilent
    @Volatile private var lastPreference: AudioSourcePreference = AudioSourcePreference.AUTO
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow<FrameMetrics?>(null)
    /** Latest frame; updated ~100 times per second on the analysis thread. */
    val metrics: StateFlow<FrameMetrics?> = _metrics.asStateFlow()

    /** Rebuilt on every [start] so the sample rate matches the opened source. */
    @Volatile var analyzer: Analyzer = Analyzer(config)
        private set

    /** Debug hook handed to every [Analyzer] this engine creates (see Analyzer.pitchTrace). */
    @Volatile var pitchTrace: ((raw: ru.dvedev.me.cupola.dsp.pitch.PitchEstimate, out: ru.dvedev.me.cupola.dsp.pitch.PitchEstimate) -> Unit)? = null
        set(value) { field = value; analyzer.pitchTrace = value }

    @Volatile private var baseConfig: AnalyzerConfig = config

    @Volatile var overruns: Long = 0
        private set

    @Volatile var readErrors: Long = 0
        private set

    /** Milliseconds from the end of the last captured chunk to its metrics being published. */
    @Volatile var processingLatencyMs: Double = 0.0
        private set

    val sourceStatus: AudioSourceStatus? get() = capture.status

    fun addListener(l: FrameListener) { listeners += l }
    fun removeListener(l: FrameListener) { listeners -= l }

    /** Applies analyzer settings that survive restarts (band, A4 …). */
    fun updateConfig(transform: (AnalyzerConfig) -> AnalyzerConfig) {
        val c = transform(baseConfig)
        baseConfig = c
        analyzer.band = c.band
        analyzer.a4Hz = c.a4Hz
        analyzer.confidenceMin = c.confidenceMin
        analyzer.includeFundamentalInOvertones = c.includeFundamentalInOvertones
        analyzer.vibratoThresholds = c.vibratoThresholds
    }

    val config: AnalyzerConfig get() = baseConfig

    @Synchronized
    fun start(preference: AudioSourcePreference = AudioSourcePreference.AUTO): Boolean {
        if (running.get()) return true
        lastPreference = preference
        _inputSilent.value = false
        val status = try {
            capture.open(preference)
        } catch (e: AudioCaptureException) {
            Log.e(TAG, "cannot open microphone", e)
            _state.value = EngineState.Error(e.message ?: "audio open failed")
            return false
        }
        analyzer = Analyzer(baseConfig.copy(sampleRate = status.sampleRate)).also { it.pitchTrace = pitchTrace }
        val hop = analyzer.hop
        val ring = FloatRingBuffer(status.sampleRate) // 1 s of headroom
        overruns = 0
        readErrors = 0
        samplesReady.drainPermits()
        running.set(true)

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(hop)
            // Silence watchdog: Android hands a client zeros instead of an error when another app
            // owns the microphone or when the client was created before the runtime grant reached
            // the audio policy (fresh install on OnePlus, 2026‑09‑16). After SILENCE_REOPEN_SECONDS
            // of digital silence, or as soon as the client reports itself silenced, the stream is
            // closed and opened again — a new client gets a fresh policy decision.
            var silentChunks = 0
            var lastReopenNanos = 0L
            var chunksSinceCheck = 0
            var reopens = 0
            try {
                capture.start()
                while (running.get()) {
                    val n = capture.read(chunk, 0, hop)
                    if (n > 0) {
                        lastChunkNanos = System.nanoTime()
                        ring.write(chunk, 0, n)
                        overruns = ring.overruns
                        samplesReady.release()
                        var zero = true
                        for (i in 0 until n) if (chunk[i] != 0f) { zero = false; break }
                        silentChunks = if (zero) silentChunks + 1 else 0
                        val silentSeconds = silentChunks * hop.toDouble() / status.sampleRate
                        val policySilenced = ++chunksSinceCheck >= 50 && run { chunksSinceCheck = 0; capture.isClientSilenced() }
                        val silent = silentSeconds >= SILENCE_FLAG_SECONDS || policySilenced
                        if (silent != _inputSilent.value) {
                            _inputSilent.value = silent
                            Log.w(TAG, if (silent) "input silent: zeros for ${"%.1f".format(silentSeconds)} s, policySilenced=$policySilenced" else "input alive again")
                        }
                        val now = System.nanoTime()
                        if ((silentSeconds >= SILENCE_REOPEN_SECONDS || policySilenced) && now - lastReopenNanos > REOPEN_INTERVAL_NS) {
                            lastReopenNanos = now
                            silentChunks = 0
                            // every other attempt takes the other source: on the OnePlus first launch the
                            // declared UNPROCESSED stayed silent for ~40 s while VOICE_RECOGNITION works at once
                            val current = capture.status?.source
                            val pref = if (reopens++ % 2 == 1 && lastPreference == AudioSourcePreference.AUTO) {
                                if (current == AudioCapture.UNPROCESSED) AudioSourcePreference.VOICE_RECOGNITION else AudioSourcePreference.UNPROCESSED
                            } else lastPreference
                            Log.w(TAG, "reopening the microphone after silence (policySilenced=$policySilenced, attempt=$reopens, preference=$pref)")
                            try {
                                capture.stop()
                                val st = capture.open(pref)
                                _state.value = EngineState.Running(st)
                                if (st.sampleRate != status.sampleRate) Log.w(TAG, "reopened at ${st.sampleRate} Hz instead of ${status.sampleRate} Hz")
                                capture.start()
                            } catch (e: AudioCaptureException) {
                                Log.e(TAG, "reopen failed", e)
                            }
                        }
                    } else if (n < 0) {
                        readErrors++
                        if (!running.get()) break
                        Thread.sleep(5)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture thread stopped", e)
                _state.value = EngineState.Error(e.message ?: "capture failed")
                running.set(false)
            } finally {
                capture.stop()
            }
        }, "cupola-capture")

        analysisThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(hop)
            val current = analyzer
            var frames = 0L
            var maxLatency = 0.0
            var lastLog = System.nanoTime()
            while (running.get()) {
                if (!samplesReady.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
                while (ring.available() >= hop) {
                    ring.read(buf, 0, hop)
                    current.push(buf) { m, spectrum ->
                        for (l in listeners) l.onFrame(m, spectrum)
                        _metrics.value = m
                        processingLatencyMs = (System.nanoTime() - lastChunkNanos) / 1e6
                        frames++
                        if (processingLatencyMs > maxLatency) maxLatency = processingLatencyMs
                    }
                }
                val now = System.nanoTime()
                if (now - lastLog > STATS_INTERVAL_NS) {
                    Log.i(TAG, "stats: frames=$frames overruns=$overruns readErrors=$readErrors latencyNow=${"%.1f".format(processingLatencyMs)}ms latencyMax=${"%.1f".format(maxLatency)}ms")
                    maxLatency = 0.0
                    lastLog = now
                }
            }
        }, "cupola-analysis")

        _state.value = EngineState.Running(status)
        captureThread?.start()
        analysisThread?.start()
        Log.i(TAG, "engine started: hop=$hop, fft=${analyzer.fftSize}, rate=${status.sampleRate}")
        return true
    }

    @Volatile private var lastChunkNanos = 0L

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        captureThread?.join(500)
        analysisThread?.join(500)
        captureThread = null
        analysisThread = null
        capture.close()
        _state.value = EngineState.Idle
        Log.i(TAG, "engine stopped (overruns=$overruns, readErrors=$readErrors)")
    }

    val isRunning: Boolean get() = running.get()

    companion object {
        /** Digital silence this long flags the input on screen … */
        const val SILENCE_FLAG_SECONDS = 1.5
        /** … and this long makes the engine reopen the stream (then at most every REOPEN_INTERVAL_NS). */
        const val SILENCE_REOPEN_SECONDS = 2.0
        const val REOPEN_INTERVAL_NS = 4_000_000_000L
        private const val TAG = "CupolaAudio"
        private const val STATS_INTERVAL_NS = 60_000_000_000L
    }
}
