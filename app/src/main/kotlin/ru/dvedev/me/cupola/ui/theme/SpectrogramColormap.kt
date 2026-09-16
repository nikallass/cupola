package ru.dvedev.me.cupola.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 256-entry lookup table mapping a quantised level (0 = silence, 255 = loudest) to a
 * packed ARGB colour. Reproduces the mock: two linear segments with a knee at 0.55 and
 * a gamma of 1.15 so that quiet content stays close to the panel colour.
 */
@Immutable
class SpectrogramColormap(private val stops: List<Color>) {
    val lut: IntArray = IntArray(SIZE)

    init {
        require(stops.size == 3) { "colormap needs exactly three stops" }
        val (c0, c1, c2) = stops
        for (i in 0 until SIZE) {
            val x = (i / (SIZE - 1).toDouble()).pow(GAMMA).toFloat()
            val (a, b, t) = if (x < KNEE) Triple(c0, c1, x / KNEE) else Triple(c1, c2, (x - KNEE) / (1f - KNEE))
            lut[i] = Color(
                red = a.red + (b.red - a.red) * t,
                green = a.green + (b.green - a.green) * t,
                blue = a.blue + (b.blue - a.blue) * t,
            ).toArgb()
        }
    }

    /** ARGB for a level in `0f..1f`. */
    fun argb(level: Float): Int = lut[(level.coerceIn(0f, 1f) * (SIZE - 1)).roundToInt()]

    /**
     * The same palette with the level remapped (owner 2026‑09‑16). k = [contrastPct] / 100:
     * below 100 % a root curve `level^k` lifts the weak levels, so even the noise floor leaves
     * grainy pixels everywhere; above 100 % the band between a threshold `t = 0.9·p·(1 − 1/k)`
     * and the running peak level [peakLevel] `p` is stretched to full darkness — so only bold
     * dark peaks remain whatever the device's absolute level. Level 0 stays white in both directions.
     */
    fun adjusted(contrastPct: Int, peakLevel: Float = 1f): SpectrogramColormap {
        if (contrastPct == 100) return this
        val out = SpectrogramColormap(this)
        val k = contrastPct / 100.0
        val p = peakLevel.toDouble().coerceIn(0.15, 1.0)
        val t = if (k > 1.0) 0.9 * p * (1.0 - 1.0 / k) else 0.0
        for (i in 0 until SIZE) {
            val x = i / (SIZE - 1).toDouble()
            val y = if (k <= 1.0) x.pow(k) else ((x - t) / (p - t)).coerceIn(0.0, 1.0)
            out.lut[i] = lut[(y * (SIZE - 1)).roundToInt().coerceIn(0, SIZE - 1)]
        }
        return out
    }

    private constructor(source: SpectrogramColormap) : this(source.stops)

    companion object {
        const val SIZE = 256
        private const val KNEE = 0.55f
        private const val GAMMA = 1.0 // the reference site maps linearly; 1.15 (mock) hid weak overtones
    }
}
