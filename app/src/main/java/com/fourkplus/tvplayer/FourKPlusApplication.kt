package com.fourkplus.tvplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Enables a crossfade for every AsyncImage in the app (Coil picks this up automatically as the
 *  default ImageLoader) so a poster that's still loading - slow provider, slow network, a big
 *  grid all loading at once - fades in once it arrives instead of popping in abruptly. Without
 *  this a slow-loading image looks identical to a missing one until the exact frame it finishes. */
class FourKPlusApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            // Artwork at the size it is drawn - see TmdbSizeMapper.
            .components { add(TmdbSizeMapper()) }
            // OkHttp fetches at most five things at once from one host by default, and almost every
            // poster comes from the one host, image.tmdb.org. A grid of twenty-odd posters was
            // fetched five at a time, the rest queued behind them. TMDB is a CDN built for this.
            .okHttpClient {
                OkHttpClient.Builder()
                    .dispatcher(Dispatcher().apply {
                        maxRequests = 32
                        maxRequestsPerHost = 12
                    })
                    .build()
            }
            .apply { if (BuildConfig.DEBUG) eventListenerFactory { ImageTimingLogger() } }
            .build()
}

/**
 * Debug builds only: one log line per image, with how long it took and where it came from, so
 * artwork speed can be measured on a real box (`adb logcat -s ImageTiming`). The host and the
 * decoded size are logged, never the link - a provider's own artwork links can carry the login.
 */
private class ImageTimingLogger : coil.EventListener {
    private var startedAt = 0L
    // Only TMDB's size segment ("w342", "original"), which says nothing about the viewer.
    private var tmdbSize = ""
    override fun onStart(request: coil.request.ImageRequest) {
        startedAt = android.os.SystemClock.elapsedRealtime()
    }
    override fun mapEnd(request: coil.request.ImageRequest, output: Any) {
        tmdbSize = Regex("/t/p/([^/]+)/").find(output.toString())?.let { " tmdb=" + it.groupValues[1] }.orEmpty()
    }
    override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
        val host = runCatching { android.net.Uri.parse(request.data.toString()).host }.getOrNull() ?: "-"
        val drawable = result.drawable
        android.util.Log.d(
            "ImageTiming",
            "ok ${android.os.SystemClock.elapsedRealtime() - startedAt} ms from ${result.dataSource} " +
                "host=$host decoded=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}$tmdbSize"
        )
    }
    override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) {
        android.util.Log.d("ImageTiming", "error ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
    }
}
