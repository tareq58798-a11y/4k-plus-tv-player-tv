package com.fourkplus.tvplayer

import android.content.Context
import coil.map.Mapper
import coil.request.Options
import coil.size.Dimension

/**
 * Asks TMDB for artwork at the size it will be drawn, not the size the provider happened to link.
 *
 * Nearly all of the owner's provider's artwork is on image.tmdb.org (on 2026-09-26, 33,631 of
 * 39,405 films and series), and almost every link asks for w600_and_h900_bestv2 - a 600x900 poster -
 * whether it lands in a grid cell a third of that wide or not. TMDB serves the same picture at a
 * handful of fixed widths from the same address with only that one path segment changed, so this
 * swaps it for the smallest one at least as big as the space the picture is going into. Measured
 * from the owner's network on six of those posters: 730 KB and 2.4 s at w600, 271 KB and 1.1 s at
 * w342, which is what a grid cell gets.
 *
 * It only ever asks for less than the link did, never more, and it leaves every other host alone.
 * Nothing needs to be known about the picture: TMDB answers any of these widths for any file.
 *
 * One cap is a choice rather than a measurement of need: nothing is fetched wider than w1280. Only
 * the app-wide background ever asks for more, and TMDB's next step up is the original, which is up
 * to 3840 wide and several times the download. Behind the background's scrims 1280 stretched to a
 * 1920 panel is not a difference anyone has pointed out, and a background that arrives in a
 * fraction of the time is the one they did.
 */
internal class TmdbSizeMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String? {
        val match = TmdbImagePath.find(data) ?: return null
        val token = match.groupValues[2]
        val linkedWidth = when {
            token == "original" -> Int.MAX_VALUE
            else -> LinkedWidth.find(token)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        }
        val width = (options.size.width as? Dimension.Pixels)?.px ?: return null
        val height = (options.size.height as? Dimension.Pixels)?.px ?: width
        // The longer side, because a crop fills whichever side runs out first and a picture of a
        // different shape from its box can need either.
        val needed = maxOf(width, height)
        val target = TmdbWidths.firstOrNull { it >= needed } ?: TmdbWidths.last()
        if (target >= linkedWidth) return null
        return data.replaceRange(match.groups[2]!!.range, "w$target")
    }

    private companion object {
        val TmdbImagePath = Regex("^https?://(image\\.tmdb\\.org|media\\.themoviedb\\.org|www\\.themoviedb\\.org)/t/p/([^/]+)/")
        val LinkedWidth = Regex("^w(\\d+)")
        val TmdbWidths = listOf(92, 154, 185, 342, 500, 780, 1280)
    }
}

/**
 * The size the app-wide background is decoded at: the screen's own, in pixels.
 *
 * It used to be a 4K ceiling, on the grounds that a 4K panel would show the difference. But the app
 * draws into a window the size of the display mode, which on a television is 1920x1080 almost
 * everywhere whatever the panel, and a 3840x2160 bitmap drawn into it is scaled down every frame -
 * four times the pixels to decode and to hold in memory, where each background took 33 MB of the
 * image cache on a box with 2 GB, pushing the posters out of it.
 */
internal fun backdropSizePx(context: Context): Pair<Int, Int> {
    val metrics = context.resources.displayMetrics
    return metrics.widthPixels to metrics.heightPixels
}
