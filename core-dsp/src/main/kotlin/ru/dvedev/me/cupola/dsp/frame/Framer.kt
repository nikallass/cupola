package ru.dvedev.me.cupola.dsp.frame

/**
 * One analysis frame. Buffers are owned by the [Framer] and reused: consumers must not
 * keep references past the callback.
 */
class Frame internal constructor(val size: Int, val hop: Int, val sampleRate: Int) {
    /** Unwindowed samples (for YIN, RMS). */
    val raw: DoubleArray = DoubleArray(size)

    /** Hann-windowed samples (for the FFT). */
    val windowed: DoubleArray = DoubleArray(size)

    /** Sequential frame number, starting at 0. */
    var index: Long = 0
        internal set

    /** Position of the first sample of this frame in the input stream. */
    val startSample: Long get() = index * hop

    /** Time of the frame centre, seconds from stream start. */
    val centerSeconds: Double get() = (startSample + size / 2.0) / sampleRate

    /** Time of the last sample of the frame — what "now" means for the UI. */
    val endSeconds: Double get() = (startSample + size).toDouble() / sampleRate
}

/**
 * Slices a sample stream into overlapping frames of [frameSize] every [hop] samples and
 * applies the analysis [window]. Feeding may be arbitrarily ragged (any chunk size);
 * frame boundaries depend only on the stream position.
 */
class Framer(
    val frameSize: Int,
    val hop: Int,
    val sampleRate: Int,
    private val window: DoubleArray = Windows.hann(frameSize),
) {
    init {
        require(hop in 1..frameSize) { "hop must be in 1..frameSize" }
        require(window.size == frameSize) { "window length must equal frameSize" }
    }

    private val buffer = DoubleArray(frameSize)
    private var filled = 0
    private val frame = Frame(frameSize, hop, sampleRate)
    private var nextIndex = 0L

    /** Number of frames emitted so far. */
    val framesEmitted: Long get() = nextIndex

    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset, onFrame: (Frame) -> Unit) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(frameSize - filled, end - i)
            for (j in 0 until n) buffer[filled + j] = samples[i + j].toDouble()
            filled += n
            i += n
            if (filled == frameSize) emit(onFrame)
        }
    }

    fun push(samples: DoubleArray, offset: Int = 0, length: Int = samples.size - offset, onFrame: (Frame) -> Unit) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(frameSize - filled, end - i)
            System.arraycopy(samples, i, buffer, filled, n)
            filled += n
            i += n
            if (filled == frameSize) emit(onFrame)
        }
    }

    fun reset() {
        filled = 0
        nextIndex = 0
    }

    private fun emit(onFrame: (Frame) -> Unit) {
        System.arraycopy(buffer, 0, frame.raw, 0, frameSize)
        for (j in 0 until frameSize) frame.windowed[j] = buffer[j] * window[j]
        frame.index = nextIndex++
        onFrame(frame)
        System.arraycopy(buffer, hop, buffer, 0, frameSize - hop)
        filled = frameSize - hop
    }

    companion object {
        /** Number of frames a stream of [samples] samples yields. */
        fun frameCount(samples: Long, frameSize: Int, hop: Int): Long =
            if (samples < frameSize) 0 else (samples - frameSize) / hop + 1
    }
}
