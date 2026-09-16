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
            try {
                capture.start()
                while (running.get()) {
                    val n = capture.read(chunk, 0, hop)
                    if (n > 0) {
                        lastChunkNanos = System.nanoTime()
                        ring.write(chunk, 0, n)
                        overruns = ring.overruns
                        samplesReady.release()
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
        private const val TAG = "CupolaAudio"
        private const val STATS_INTERVAL_NS = 60_000_000_000L
    }
}
