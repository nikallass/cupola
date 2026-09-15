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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null

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
                    Log.i(TAG, "opened ${it.sourceName} @ ${it.sampleRate} Hz, buffer ${it.bufferSamples} samples (${"%.0f".format(it.bufferMillis)} ms), processing=${it.processing}, unprocessedDeclared=$declared")
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
