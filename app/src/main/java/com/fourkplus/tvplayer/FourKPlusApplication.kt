package com.fourkplus.tvplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

/** Enables a crossfade for every AsyncImage in the app (Coil picks this up automatically as the
 *  default ImageLoader) so a poster that's still loading - slow provider, slow network, a big
 *  grid all loading at once - fades in once it arrives instead of popping in abruptly. Without
 *  this a slow-loading image looks identical to a missing one until the exact frame it finishes. */
class FourKPlusApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
