package com.fourkplus.tvplayer.data

enum class PlaylistKind { M3U_URL, PROVIDER_LOGIN, DEVICE_ACTIVATION }

data class PlaylistInput(
    val name: String,
    val kind: PlaylistKind,
    val address: String,
    val username: String = "",
    val password: String = ""
) {
    override fun toString() = "PlaylistInput(credentials=REDACTED)"
}

/** Stable identity for a source across saved-source storage and on-disk cache naming. Deliberately excludes the password. */
internal fun PlaylistInput.sourceId(): String = "${kind.name}|${address.trim()}|${username.trim()}"

enum class MediaKind { LIVE, MOVIE, SERIES }

data class PlaylistItem(
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String?,
    val channelId: String?,
    val kind: MediaKind,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    /**
     * When the provider added this title to their catalogue, in epoch seconds. Null for sources
     * that do not report it (plain M3U playlists, and panels that omit the field) - those titles
     * are simply absent from "recently added" rather than being guessed at a position.
     */
    val addedEpochSeconds: Long? = null
) {
    override fun toString() = "PlaylistItem(credentials=REDACTED)"
}

data class LoadedPlaylist(
    val name: String,
    val items: List<PlaylistItem>,
    val groups: List<String>,
    val accountStatus: String? = null,
    val expiryEpochSeconds: Long? = null
) {
    val liveCount: Int get() = items.count { it.kind == MediaKind.LIVE }
    val movieCount: Int get() = items.count { it.kind == MediaKind.MOVIE }
    val seriesCount: Int get() = items.count { it.kind == MediaKind.SERIES }
}

data class MovieDetailsInfo(
    val originalTitle: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val trailerUrl: String? = null
)


data class SeriesEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val thumbnailUrl: String? = null,
    val duration: String? = null,
    val description: String? = null
)

/** One programme entry from a provider's EPG (electronic programme guide), in absolute epoch
 *  seconds so on-screen "now" comparisons don't depend on the provider's own timezone. */
data class EpgProgram(
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long
)

/** The currently-airing and up-next programme for one live channel, resolved against the current
 *  time — either may be null if the guide has a gap or the provider returned nothing usable. */
data class EpgNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null
)

data class SeriesDetailsInfo(
    val originalTitle: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val trailerUrl: String? = null,
    val episodes: List<SeriesEpisode> = emptyList()
) {
    val seasons: List<Int> get() = episodes.map { it.seasonNumber }.distinct().sorted()
}
