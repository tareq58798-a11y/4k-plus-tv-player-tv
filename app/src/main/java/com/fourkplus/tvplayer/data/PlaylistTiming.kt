package com.fourkplus.tvplayer.data

/** Fixed stage labels only: never pass URLs, account identifiers or exception messages here. */
internal object PlaylistTiming {
    inline fun <T> measure(stage: String, block: () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        var succeeded = false
        try { return block().also { succeeded = true } }
        finally {
            android.util.Log.i("PlaylistTiming", "$stage ms=${android.os.SystemClock.elapsedRealtime() - started} success=$succeeded")
        }
    }
}
