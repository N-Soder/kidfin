// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks a card tone from the artwork itself, so the border and lip match the picture.
 * Cached per item so scrolling back never re-analyses.
 */
internal object ArtworkTones {
    private val cache = LruCache<String, Tone>(256)

    fun cached(itemId: String): Tone? = cache[itemId]

    suspend fun extract(itemId: String, bitmap: Bitmap): Tone? {
        cache[itemId]?.let { return it }
        val tone = withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.mutedSwatch
                ?: palette.dominantSwatch
            swatch?.rgb?.let(::toneFrom)
        } ?: return null
        cache.put(itemId, tone)
        return tone
    }

    /** Keeps the hue, but forces readable lightness on the dark Storybook background. */
    internal fun toneFrom(rgb: Int): Tone {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        val hue = hsl[0]
        val sat = hsl[1].coerceIn(0.35f, 0.85f)
        return Tone(
            strong = hslColor(hue, sat, hsl[2].coerceIn(0.55f, 0.70f)),
            soft = hslColor(hue, sat * 0.6f, 0.18f),
            ink = hslColor(hue, sat, 0.80f),
        )
    }

    private fun hslColor(h: Float, s: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, l)))
}
