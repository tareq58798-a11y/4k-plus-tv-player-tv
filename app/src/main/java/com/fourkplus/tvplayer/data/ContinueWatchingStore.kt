package com.fourkplus.tvplayer.data

import android.content.Context

/** Tracks the single most-recently-watched movie or series episode across screens, so Home's
 *  "Continue Watching" card can show real content instead of a static placeholder. Each screen's
 *  own progress store (movie_library / series_library) remains the source of truth for resume
 *  position; this just records which of those items was touched most recently. */
object ContinueWatchingStore {
    private const val PREFS = "continue_watching"

    data class Entry(val kind: MediaKind, val itemKey: String, val episodeId: String?)

    fun record(context: Context, kind: MediaKind, itemKey: String, episodeId: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind", kind.name)
            .putString("item_key", itemKey)
            .putString("episode_id", episodeId)
            .apply()
    }

    /** Clears the record only if it currently points at [itemKey], so finishing an unrelated item
     *  doesn't wipe out a different in-progress one. */
    fun clear(context: Context, itemKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("item_key", null) == itemKey) prefs.edit().clear().apply()
    }

    fun read(context: Context): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = prefs.getString("kind", null)?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            ?: return null
        val itemKey = prefs.getString("item_key", null) ?: return null
        return Entry(kind, itemKey, prefs.getString("episode_id", null))
    }
}
