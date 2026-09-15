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
class SpectrogramColormap(stops: List<Color>) {
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

    companion object {
        const val SIZE = 256
        private const val KNEE = 0.55f
        private const val GAMMA = 1.15
    }
}
