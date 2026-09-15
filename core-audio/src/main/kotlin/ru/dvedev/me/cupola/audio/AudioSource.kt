package ru.dvedev.me.cupola.audio

import android.media.MediaRecorder

/** Settings → «Источник аудио». */
enum class AudioSourcePreference { AUTO, UNPROCESSED, VOICE_RECOGNITION }

/** What the platform is (probably) doing to the signal before we see it. */
enum class ProcessingState {
    /** `UNPROCESSED` on a device that declares support: no AGC, no noise suppression. */
    DISABLED,

    /** `VOICE_RECOGNITION`: the spec forbids AGC/NS here, but vendors deviate. */
    NOT_GUARANTEED,

    /** `UNPROCESSED` forced on a device that does not declare support: it may behave like `DEFAULT`. */
    UNVERIFIED,
}

/** Report of the opened capture path, shown in settings and logged on start (T-040). */
data class AudioSourceStatus(
    val source: Int,
    val sampleRate: Int,
    /** Samples the platform buffer holds (`getMinBufferSize × 4`). */
    val bufferSamples: Int,
    val processing: ProcessingState,
    val unprocessedDeclared: Boolean,
) {
    val sourceName: String
        get() = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "SOURCE_$source"
        }

    val bufferMillis: Double get() = 1000.0 * bufferSamples / sampleRate
}

class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
