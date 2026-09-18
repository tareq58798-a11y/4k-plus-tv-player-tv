package com.fourkplus.tvplayer

import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * How a stream is fetched and decoded: the HTTP clients, the buffering policy, the track selector
 * and the player they are assembled into.
 *
 * Shared between the television and phone builds on purpose. None of it describes a screen - it is
 * the connection tuning, the decoder fallback and the buffering compromise worked out against real
 * provider streams, and a phone benefits from every one of those as much as a television does.
 * Keeping one copy is what stops the two builds drifting into different playback behaviour.
 *
 * Anything that draws - the controls, the focus handling, the overlays - stays with its own build.
 */

private val playbackConnectionPool by lazy { ConnectionPool(8, 10, TimeUnit.MINUTES) }

/** Both modes share one connection pool, so switching mode never costs the warm sockets that make
 *  channel changes quick. Only the patience differs: a slow link needs long enough to finish a
 *  request that is crawling but alive, while on a fast link the same wait is just a stalled error
 *  banner the viewer sits in front of for half a minute. */
private fun buildPlaybackClient(timeoutSeconds: Long) = OkHttpClient.Builder()
    .connectionPool(playbackConnectionPool)
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private val fastPlaybackClient: OkHttpClient by lazy { buildPlaybackClient(8) }
private val slowPlaybackClient: OkHttpClient by lazy { buildPlaybackClient(30) }

/**
 * Which way the player resolves the one genuine conflict in its tuning: how long to wait before
 * showing a picture. Every other setting here can serve both ends at once, but this one cannot -
 * starting from a thin buffer is what makes channel changes feel instant, and it is also exactly
 * what makes a struggling connection stall a moment later. Rather than split the difference and
 * serve neither well, the user picks which side they are on.
 */
internal enum class ConnectionMode {
    /** Start the picture as soon as possible and keep the next channel warm. Assumes bandwidth is
     *  cheap and plentiful. */
    FAST,

    /** Start once, with enough buffered to survive a dip, and never spend bandwidth on anything
     *  the viewer has not asked to watch. */
    SLOW;

    companion object {
        private const val PREFS = "playback_settings"
        const val KEY = "connection_mode"

        fun read(context: android.content.Context): ConnectionMode {
            val stored = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY, FAST.name)
            return runCatching { valueOf(stored.orEmpty()) }.getOrDefault(FAST)
        }
    }
}

internal fun buildFourKPlusExoPlayer(context: android.content.Context, skipSeconds: Int, muted: Boolean): ExoPlayer {
    val mode = ConnectionMode.read(context)
    val dataSourceFactory = OkHttpDataSource.Factory(
        if (mode == ConnectionMode.SLOW) slowPlaybackClient else fastPlaybackClient
    )
        .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
        .setTransferListener(DefaultBandwidthMeter.getSingletonInstance(context))
    val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(dataSourceFactory)
        // A dropped segment is normal on a weak link and worth retrying hard; on a fast one a
        // failure is far more likely to be a genuinely dead stream, where labouring through eight
        // retries just delays the error the viewer needs to see.
        .setLoadErrorHandlingPolicy(
            DefaultLoadErrorHandlingPolicy(if (mode == ConnectionMode.SLOW) 8 else 3)
        )
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        // Shared process-wide, so a connection already measured on one channel is known before the
        // next stream starts instead of every player restarting from a cold country-average guess.
        .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
        .setLoadControl(fourKPlusLoadControl(mode))
        .setTrackSelector(fourKPlusTrackSelector(context, mode))
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                // Provider streams carry whatever the panel muxed, and a device's primary decoder
                // occasionally refuses an unusual HEVC/AVC profile outright. Without fallback that
                // is a hard playback error; with it, playback continues on another decoder.
                .setEnableDecoderFallback(true)
        )
        .setSeekBackIncrementMs(skipSeconds * 1_000L)
        .setSeekForwardIncrementMs(skipSeconds * 1_000L)
        .build()
        .apply { volume = if (muted) 0f else 1f }
}

/**
 * Buffering, tuned per [ConnectionMode]. Thresholds are time-based rather than byte-based in both
 * modes so a high-bitrate stream is never cut short of seconds just for being large.
 *
 * FAST shows the picture after half a second, because on a healthy link the stream refills far
 * faster than it plays and making the viewer wait buys nothing. SLOW waits until it holds a real
 * cushion before starting, and hoards up to four minutes once running: on a link that cannot
 * reliably sustain the stream, one longer wait at the start is worth far more than a fast start
 * followed by repeated stalls.
 */
private fun fourKPlusLoadControl(mode: ConnectionMode): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        /* minBufferMs = */ if (mode == ConnectionMode.SLOW) 50_000 else 15_000,
        /* maxBufferMs = */ if (mode == ConnectionMode.SLOW) 240_000 else 60_000,
        /* bufferForPlaybackMs = */ if (mode == ConnectionMode.SLOW) 2_500 else 500,
        /* bufferForPlaybackAfterRebufferMs = */ if (mode == ConnectionMode.SLOW) 8_000 else 2_000
    )
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()

/** Never let the display's own size cap what gets decoded. media3 defaults the viewport to the
 *  physical screen, which is the right call for picking among adaptive renditions but means a
 *  panel that under-reports its size can hold a stream below what it is actually sending. These
 *  streams are single-bitrate anyway, so the only thing that constraint can do here is take
 *  quality away. */
private fun fourKPlusTrackSelector(context: android.content.Context, mode: ConnectionMode): DefaultTrackSelector =
    DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .clearViewportSizeConstraints()
                .setExceedVideoConstraintsIfNecessary(true)
                // Never reject a stream for exceeding what the device claims it can handle: a
                // refused track is a black screen, whereas attempting it usually just works.
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                // Only meaningful where a stream offers several renditions. On a connection the
                // user has told us is fast, pin the best one instead of letting the estimator
                // creep downward; on a slow one let it adapt, because forcing the top rendition on
                // a link that cannot carry it produces constant rebuffering, not better picture.
                .setForceHighestSupportedBitrate(mode == ConnectionMode.FAST)
        )
    }
