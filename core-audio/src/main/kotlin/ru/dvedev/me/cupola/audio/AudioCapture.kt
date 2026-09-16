package ru.dvedev.me.cupola.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ru.dvedev.me.cupola.dsp.AudioFormatDefaults

/**
 * Opens the microphone the way SPEC §3 demands: `UNPROCESSED` when the device declares
 * support, otherwise `VOICE_RECOGNITION`; never `MIC`/`DEFAULT`, never a `NoiseSuppressor`
 * or `AutomaticGainControl`. Float mono PCM at 48 kHz (44.1 kHz fallback), platform
 * buffer of `getMinBufferSize × 4`.
 */
class AudioCapture(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null

    /**
     * Everything that decides whether Android hands this app audio or silence (owner 2026‑09‑16:
     * long silenced starts on a OnePlus): permission and app-op, process importance, audio mode,
     * mic mute, our own recording configuration and every active capture in the system.
     */
    fun diagnostics(): String = runCatching {
        val sb = StringBuilder()
        val perm = appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        sb.append("perm=").append(perm)
        val ops = appContext.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val opMode = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), appContext.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), appContext.packageName)
        }.getOrDefault(-1)
        sb.append(" appop=").append(when (opMode) { 0 -> "allowed"; 1 -> "ignored"; 2 -> "errored"; 3 -> "default"; 4 -> "foreground"; else -> opMode.toString() })
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        sb.append(" importance=").append(info.importance)
        sb.append(" audioMode=").append(audioManager.mode).append(" micMute=").append(audioManager.isMicrophoneMute)
        val r = record
        sb.append(" ourSession=").append(r?.audioSessionId ?: -1).append(" recState=").append(r?.recordingState ?: -1)
        if (android.os.Build.VERSION.SDK_INT >= 30 && r != null) sb.append(" privacySensitive=").append(r.isPrivacySensitive)
        if (android.os.Build.VERSION.SDK_INT >= 29 && r != null) {
            val own = r.activeRecordingConfiguration
            sb.append(" own=").append(if (own == null) "none" else "src${own.clientAudioSource}/silenced=${own.isClientSilenced}/dev=${own.audioDevice?.type}")
        }
        val active = audioManager.activeRecordingConfigurations
        sb.append(" active=").append(active.size).append("[")
        active.forEachIndexed { i, c ->
            if (i > 0) sb.append("; ")
            sb.append("src").append(c.clientAudioSource).append("/session").append(c.clientAudioSessionId)
            if (android.os.Build.VERSION.SDK_INT >= 29) sb.append("/silenced=").append(c.isClientSilenced)
            if (c.clientAudioSessionId == r?.audioSessionId) sb.append("/ours")
        }
        sb.append("]")
        sb.toString()
    }.getOrElse { "diagnostics failed: $it" }

    /** True when another app's capture is active and not silenced while ours is silenced — the microphone is taken. */
    fun micTakenByOther(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val session = record?.audioSessionId ?: return false
        val configs = audioManager.activeRecordingConfigurations
        val own = configs.firstOrNull { it.clientAudioSessionId == session }
        (own == null || own.isClientSilenced) && configs.any { it.clientAudioSessionId != session && !it.isClientSilenced }
    }.getOrDefault(false)

    private var callback: AudioManager.AudioRecordingCallback? = null

    /** Journals every change of our capture's silenced flag, the moment the system un-silences us. */
    fun watchRecordingConfig() {
        if (android.os.Build.VERSION.SDK_INT < 29 || callback != null) return
        var lastSilenced: Boolean? = null
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                val session = record?.audioSessionId ?: return
                val own = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                if (own.isClientSilenced != lastSilenced) {
                    lastSilenced = own.isClientSilenced
                    MicJournal.add("recording config: silenced=${own.isClientSilenced}, others=${configs.size - 1}", warn = own.isClientSilenced)
                }
            }
        }
        runCatching { audioManager.registerAudioRecordingCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) }
        callback = cb
    }

    fun unwatchRecordingConfig() {
        callback?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
        callback = null
    }

    var status: AudioSourceStatus? = null
        private set

    val unprocessedDeclared: Boolean
        get() = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /** Requires `RECORD_AUDIO`; the caller checks the permission. */
    @SuppressLint("MissingPermission")
    fun open(preference: AudioSourcePreference): AudioSourceStatus {
        close()
        val declared = unprocessedDeclared
        val candidates = when (preference) {
            AudioSourcePreference.AUTO ->
                if (declared) listOf(UNPROCESSED, VOICE_RECOGNITION) else listOf(VOICE_RECOGNITION)
            AudioSourcePreference.UNPROCESSED -> listOf(UNPROCESSED, VOICE_RECOGNITION)
            AudioSourcePreference.VOICE_RECOGNITION -> listOf(VOICE_RECOGNITION)
        }
        var lastError: Throwable? = null
        for (source in candidates) {
            for (rate in SAMPLE_RATES) {
                val minBytes = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
                if (minBytes <= 0) continue
                val bufferBytes = minBytes * BUFFER_MULTIPLIER
                val candidate = try {
                    AudioRecord.Builder()
                        .apply {
                            // privacy-sensitive capture (Android 11+): the OnePlus logs of 2026‑09‑16 showed another
                            // app holding a CAMCORDER capture — a privacy-sensitive source that wins over ordinary
                            // captures, so we heard zeros while on screen. Between two privacy-sensitive captures
                            // the most recent starter gets the audio, so opening (and reopening) wins it back.
                            if (android.os.Build.VERSION.SDK_INT >= 30) setPrivacySensitive(true)
                        }
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(ENCODING)
                                .setSampleRate(rate)
                                .setChannelMask(CHANNEL)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                } catch (e: Exception) {
                    lastError = e
                    null
                } ?: continue
                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    candidate.release()
                    continue
                }
                record = candidate
                val processing = when {
                    source == VOICE_RECOGNITION -> ProcessingState.NOT_GUARANTEED
                    declared -> ProcessingState.DISABLED
                    else -> ProcessingState.UNVERIFIED
                }
                return AudioSourceStatus(source, rate, bufferBytes / BYTES_PER_SAMPLE, processing, declared).also {
                    status = it
                    MicJournal.add("opened ${it.sourceName} @ ${it.sampleRate} Hz, buffer ${it.bufferSamples} samples (${"%.0f".format(it.bufferMillis)} ms), processing=${it.processing}, unprocessedDeclared=$declared")
                }
            }
        }
        throw AudioCaptureException("no usable audio source (tried $candidates)", lastError)
    }

    fun start() {
        val r = record ?: throw AudioCaptureException("capture not opened")
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioCaptureException("AudioRecord did not start")
    }

    /** Blocking read of up to [length] floats; returns the count or a negative `AudioRecord` error. */
    fun read(dst: FloatArray, offset: Int, length: Int): Int {
        val r = record ?: return AudioRecord.ERROR_INVALID_OPERATION
        return r.read(dst, offset, length, AudioRecord.READ_BLOCKING)
    }

    fun stop() {
        record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
    }

    /**
     * True when the system hands this client silence by policy (another app owns the mic, or the
     * client was created before the runtime grant reached the audio policy — the OnePlus
     * first-launch case). Android 10+; false when unknown.
     */
    fun isClientSilenced(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return runCatching { record?.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
    }

    fun close() {
        record?.release()
        record = null
        status = null
    }

    companion object {
        private const val TAG = "CupolaAudio"
        const val UNPROCESSED = MediaRecorder.AudioSource.UNPROCESSED
        const val VOICE_RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val SAMPLE_RATES = intArrayOf(AudioFormatDefaults.SAMPLE_RATE, AudioFormatDefaults.FALLBACK_SAMPLE_RATE)
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        const val BYTES_PER_SAMPLE = 4
        const val BUFFER_MULTIPLIER = 4
    }
}
