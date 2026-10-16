package com.fourkplus.tvplayer.data

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import com.fourkplus.tvplayer.trustModernRoots
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The name the app gives providers, for playback (PlayerEngine) and for logins and lists alike.
 *
 * Logins and lists used to go out under OkHttp's own default, "okhttp/4.12.0", while playback said
 * VLC. Many panels refuse that default - it is what scripts and scrapers send - and some answer with
 * codes no browser would, such as HTTP 513: a provider that works in every other player failed here
 * at sign-in or on switching to it. One name, the one panels already accept from this app's player.
 */
internal const val PROVIDER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/**
 * Network access to Xtream Codes-style provider panels (player_api.php auth, category/stream
 * listing, movie/series detail lookups) and plain M3U playlists. Pure networking/parsing — no
 * on-device storage or saved-source concerns, which live in [PlaylistSourceStore]/[PlaylistCacheStore].
 */
internal class XtreamProviderClient {

    suspend fun load(input: PlaylistInput): LoadedPlaylist = withContext(Dispatchers.IO) {
        when (input.kind) {
            PlaylistKind.M3U_URL -> loadM3u(input)
            PlaylistKind.PROVIDER_LOGIN -> loadProvider(input)
            // Never reached: PlaylistRepository resolves this kind before calling here.
            PlaylistKind.DEVICE_ACTIVATION -> throw IllegalStateException("DEVICE_ACTIVATION must be resolved before loading.")
        }
    }

    /** Publishes usable Live TV before requesting the on-demand catalogues.
     * A failure after publishing must not retry a different host or discard live playback. */
    suspend fun loadProgressively(
        input: PlaylistInput,
        onPartial: suspend (LoadedPlaylist) -> Unit
    ): LoadedPlaylist = withContext(Dispatchers.IO) {
        if (input.kind == PlaylistKind.M3U_URL) {
            return@withContext PlaylistTiming.measure("m3u_download_parse") { loadM3u(input) }
        }
        var lastError: Exception? = null
        var published = false
        for (server in addressCandidates(input.address).map(::normalizeServerBase)) {
            try {
                return@withContext coroutineScope {
                    val auth = PlaylistTiming.measure("provider_auth") {
                        JSONObject(download(apiUrl(server, input, null)).trimStart(Char(0xFEFF)))
                    }
                    val user = auth.optJSONObject("user_info")
                        ?: throw IllegalArgumentException("Invalid provider response.")
                    val status = user.optString("status")
                    require(user.optInt("auth") == 1 && !status.equals("Disabled", true) && !status.equals("Expired", true)) {
                        "The provider rejected this account."
                    }
                    val expiry = user.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }
                    fun snapshot(items: List<PlaylistItem>) = LoadedPlaylist(
                        input.name.trim(), items, items.map { it.group }.distinct(),
                        status.takeIf(String::isNotBlank), expiry
                    )
                    val groups = progressiveCategories(apiUrl(server, input, "get_live_categories"))
                    val live = PlaylistTiming.measure("live_download_parse") { liveItems(apiUrl(server, input, "get_live_streams"), groups, server, input) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (live.isNotEmpty()) {
                        published = true
                        onPartial(snapshot(live))
                    }
                    // Capture each catalogue failure so one failed response cannot cancel
                    // the sibling request or the already usable live list.
                    //
                    // Each list is streamed (streamObjects), so neither holds its text or a tree of
                    // it - only the items kept. OutOfMemoryError is an Error, not an Exception, so
                    // it is still caught by name and turned into an ordinary failure for a
                    // catalogue too large for the box; the live list already published survives.
                    val movies = async {
                        try {
                            val movieGroups = progressiveCategories(apiUrl(server, input, "get_vod_categories"))
                            Result.success(PlaylistTiming.measure("movies_download_parse") { movieItems(apiUrl(server, input, "get_vod_streams"), movieGroups, server, input) })
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { Result.failure<List<PlaylistItem>>(e) }
                        catch (e: OutOfMemoryError) { Result.failure<List<PlaylistItem>>(outOfMemory(e)) }
                    }
                    val series = async {
                        try {
                            val seriesGroups = progressiveCategories(apiUrl(server, input, "get_series_categories"))
                            Result.success(PlaylistTiming.measure("series_download_parse") { seriesItems(apiUrl(server, input, "get_series"), seriesGroups) })
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { Result.failure<List<PlaylistItem>>(e) }
                        catch (e: OutOfMemoryError) { Result.failure<List<PlaylistItem>>(outOfMemory(e)) }
                    }
                    val movieResult = movies.await()
                    val withMovies = live + movieResult.getOrDefault(emptyList())
                    if (movieResult.isSuccess && withMovies.isNotEmpty()) {
                        published = true
                        onPartial(snapshot(withMovies))
                    }
                    val seriesResult = series.await()
                    val all = withMovies + seriesResult.getOrDefault(emptyList())
                    if (all.isNotEmpty() && (movieResult.isFailure || seriesResult.isFailure)) {
                        published = true
                        onPartial(snapshot(all))
                    }
                    movieResult.getOrThrow()
                    seriesResult.getOrThrow()
                    require(all.isNotEmpty()) { "This account contains no available content." }
                    snapshot(all)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (published) throw e
                lastError = e
            }
        }
        throw lastError ?: IllegalArgumentException("The provider could not be reached.")
    }

    private fun outOfMemory(cause: OutOfMemoryError): Exception =
        IllegalStateException("This device does not have enough memory to load the whole playlist.", cause)

    private fun progressiveCategories(url: String): Map<String, String> = try {
        categories(url)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { emptyMap() }

    suspend fun movieDetails(source: PlaylistInput, movie: PlaylistItem): MovieDetailsInfo = withContext(Dispatchers.IO) {
        val movieId = requireNotNull(movie.channelId)
        var lastError: Exception? = null
        for (server in addressCandidates(source.address).map(::normalizeServerBase)) {
            try {
                val root = JSONObject(download(apiUrl(server, source, "get_vod_info") + "&vod_id=${encode(movieId)}"))
                val info = root.optJSONObject("info") ?: root
                val movieData = root.optJSONObject("movie_data")
                val backdrop = info.optJSONArray("backdrop_path")?.let { array ->
                    (0 until array.length()).asSequence().map { array.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() && !it.equals("null", true) }
                } ?: info.optText("backdrop_path")?.takeIf { it.startsWith("http", true) }
                val trailer = info.optText("youtube_trailer")?.let { value ->
                    if (value.startsWith("http", true)) value else "https://www.youtube.com/watch?v=$value"
                }
                val originalTitle = (
                    firstText(info, "o_name", "original_name", "original_title", "title", "name")
                        ?: movieData?.let { firstText(it, "o_name", "original_name", "original_title", "name") }
                    )?.takeIf(::containsLatinText)
                return@withContext MovieDetailsInfo(
                    originalTitle = originalTitle,
                    description = firstText(info, "plot", "description"),
                    year = firstText(info, "year", "releasedate", "releaseDate")?.take(4),
                    rating = firstText(info, "rating")?.takeUnless { it == "0" || it == "0.0" },
                    duration = firstText(info, "duration", "duration_secs"),
                    genre = firstText(info, "genre"),
                    cast = firstText(info, "cast", "actors"),
                    director = firstText(info, "director"),
                    backdropUrl = backdrop,
                    posterUrl = firstText(info, "movie_image", "cover_big", "cover")
                        ?: movieData?.optText("stream_icon"),
                    trailerUrl = trailer
                )
            } catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("Movie information could not be loaded.")
    }

    suspend fun seriesDetails(source: PlaylistInput, series: PlaylistItem): SeriesDetailsInfo = withContext(Dispatchers.IO) {
        val seriesId = requireNotNull(series.channelId)
        var lastError: Exception? = null
        for (server in addressCandidates(source.address).map(::normalizeServerBase)) {
            try {
                val root = JSONObject(download(apiUrl(server, source, "get_series_info") + "&series_id=${encode(seriesId)}"))
                val info = root.optJSONObject("info") ?: JSONObject()
                val backdrop = info.optJSONArray("backdrop_path")?.let { array ->
                    (0 until array.length()).asSequence().map { array.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() && !it.equals("null", true) }
                } ?: info.optText("backdrop_path")?.takeIf { it.startsWith("http", true) }
                val trailer = info.optText("youtube_trailer")?.let { value ->
                    if (value.startsWith("http", true)) value else "https://www.youtube.com/watch?v=$value"
                }
                val episodesObject = root.optJSONObject("episodes") ?: JSONObject()
                val episodes = buildList {
                    val seasonKeys = episodesObject.keys()
                    while (seasonKeys.hasNext()) {
                        val seasonKey = seasonKeys.next()
                        val seasonNumber = seasonKey.toIntOrNull() ?: continue
                        val seasonEpisodes = episodesObject.optJSONArray(seasonKey) ?: continue
                        for (index in 0 until seasonEpisodes.length()) {
                            val episode = seasonEpisodes.optJSONObject(index) ?: continue
                            val id = episode.optString("id")
                            if (id.isBlank()) continue
                            val episodeInfo = episode.optJSONObject("info") ?: JSONObject()
                            // Panels report the container in one of two places: on the episode
                            // itself, or nested inside its info block. Reading only the first and
                            // falling back to "mp4" meant a panel that reports it the second way
                            // had every episode requested as the wrong file - the server answers
                            // with an error page, and the player reports it as an unsupported
                            // container (ExoPlayer 3003) because that is what it was handed.
                            val extension = episode.optText("container_extension")
                                ?: episodeInfo.optText("container_extension")
                                ?: "mp4"
                            val episodeNumber = episode.optInt("episode_num", index + 1)
                            add(
                                SeriesEpisode(
                                    id = id,
                                    seasonNumber = seasonNumber,
                                    episodeNumber = episodeNumber,
                                    title = firstText(episode, "title", "name")
                                        ?: "Episode $episodeNumber",
                                    streamUrl = "$server/series/${encode(source.username)}/${encode(source.password)}/$id.$extension",
                                    thumbnailUrl = firstText(episodeInfo, "movie_image", "cover_big", "cover"),
                                    duration = firstText(episodeInfo, "duration", "duration_secs"),
                                    description = firstText(episodeInfo, "plot", "description")
                                )
                            )
                        }
                    }
                }.sortedWith(compareBy<SeriesEpisode> { it.seasonNumber }.thenBy { it.episodeNumber })
                return@withContext SeriesDetailsInfo(
                    originalTitle = firstText(info, "o_name", "original_name", "original_title", "name")
                        ?.takeIf(::containsLatinText),
                    description = firstText(info, "plot", "description"),
                    year = firstText(info, "year", "releaseDate", "releasedate")?.take(4),
                    rating = firstText(info, "rating")?.takeUnless { it == "0" || it == "0.0" },
                    genre = firstText(info, "genre"),
                    cast = firstText(info, "cast", "actors"),
                    director = firstText(info, "director"),
                    backdropUrl = backdrop,
                    posterUrl = firstText(info, "cover_big", "cover") ?: series.logoUrl,
                    trailerUrl = trailer,
                    episodes = episodes
                )
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalArgumentException("Series information could not be loaded.")
    }

    /** Xtream's "short EPG" for one channel: a handful of upcoming programme entries starting
     *  from roughly now. Callers resolve which entry is "current" vs "next" themselves, since
     *  panels vary on whether the in-progress programme is included. */
    suspend fun shortEpg(source: PlaylistInput, channelId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (server in addressCandidates(source.address).map(::normalizeServerBase)) {
            try {
                val url = apiUrl(server, source, "get_short_epg") + "&stream_id=${encode(channelId)}&limit=4"
                val root = JSONObject(download(url))
                val listings = root.optJSONArray("epg_listings") ?: JSONArray()
                return@withContext buildList {
                    for (index in 0 until listings.length()) {
                        val entry = listings.optJSONObject(index) ?: continue
                        val title = decodeEpgText(entry.optString("title")) ?: continue
                        val start = entry.optString("start_timestamp").toLongOrNull() ?: continue
                        val end = entry.optString("stop_timestamp").toLongOrNull() ?: continue
                        if (end <= start) continue
                        add(EpgProgram(title, start, end))
                    }
                }.sortedBy { it.startEpochSeconds }
            } catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("Programme information could not be loaded.")
    }

    /** Xtream typically base64-encodes EPG text fields, but some panels send plain text — fall
     *  back to the raw value rather than dropping the entry if decoding doesn't look right. */
    private fun decodeEpgText(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8).trim() }.getOrNull()
        return decoded?.takeIf(String::isNotBlank) ?: trimmed
    }

    private fun loadM3u(input: PlaylistInput): LoadedPlaylist {
        var lastError: Exception? = null
        for (address in addressCandidates(input.address)) {
            try { return M3uParser.parse(input.name, download(address)) }
            catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("The playlist address could not be reached.")
    }

    private suspend fun loadProvider(input: PlaylistInput): LoadedPlaylist {
        var lastError: Exception? = null
        for (server in addressCandidates(input.address).map(::normalizeServerBase)) {
            try { return loadProviderFromServer(input, server) }
            catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("The provider could not be reached.")
    }

    private suspend fun loadProviderFromServer(input: PlaylistInput, server: String): LoadedPlaylist = coroutineScope {
        val auth = JSONObject(download(apiUrl(server, input, null)).trimStart(Char(0xFEFF)))
        val userInfo = auth.optJSONObject("user_info")
            ?: throw IllegalArgumentException("This server did not return a compatible provider login response.")
        val authenticated = userInfo.optInt("auth", 0) == 1
        val status = userInfo.optString("status", "")
        require(authenticated && !status.equals("Disabled", true) && !status.equals("Expired", true)) {
            "The provider rejected this username or password, or the account is inactive."
        }

        // These six requests are all independent of each other (categories are only needed to
        // label items once the lists come back), so firing them together instead of one after
        // another cuts total load time from their sum down to roughly the slowest single one -
        // significant here since get_vod_streams/get_series responses can be tens of thousands
        // of entries and several megabytes each.
        val liveCategoriesDeferred = async { runCatching { categories(apiUrl(server, input, "get_live_categories")) }.getOrDefault(emptyMap()) }
        val movieCategoriesDeferred = async { runCatching { categories(apiUrl(server, input, "get_vod_categories")) }.getOrDefault(emptyMap()) }
        val seriesCategoriesDeferred = async { runCatching { categories(apiUrl(server, input, "get_series_categories")) }.getOrDefault(emptyMap()) }
        // The three lists are streamed, each into its items as it arrives (streamObjects), so no
        // response is ever held whole as text or as an org.json tree - the difference between
        // loading and running out of memory on a low-memory box with a large account.
        val liveDeferred = async { liveItems(apiUrl(server, input, "get_live_streams"), liveCategoriesDeferred.await(), server, input) }
        val moviesDeferred = async { movieItems(apiUrl(server, input, "get_vod_streams"), movieCategoriesDeferred.await(), server, input) }
        val seriesDeferred = async { seriesItems(apiUrl(server, input, "get_series"), seriesCategoriesDeferred.await()) }

        val items = buildList {
            addAll(liveDeferred.await())
            addAll(moviesDeferred.await())
            addAll(seriesDeferred.await())
        }
        require(items.isNotEmpty()) { "The account connected successfully but contains no available content." }
        val expiry = userInfo.optString("exp_date").toLongOrNull()?.takeIf { it > 0L }
            ?: userInfo.optLong("exp_date", 0L).takeIf { it > 0L }
        LoadedPlaylist(
            name = input.name.trim(),
            items = items,
            groups = items.map { it.group }.distinct(),
            accountStatus = status.takeIf(String::isNotBlank),
            expiryEpochSeconds = expiry
        )
    }

    private fun categories(url: String): Map<String, String> {
        val array = JSONArray(download(url))
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                put(item.optString("category_id"), item.optString("category_name", "Other"))
            }
        }
    }

    /**
     * Streams the list at [url] and calls [onItem] with each entry, one small JSONObject at a time.
     *
     * The lists used to be downloaded whole into one String and parsed into one org.json tree. On a
     * large account that cannot fit: a 70-million-character film list needs a single 268 MB buffer
     * just to hold the text - more than a Mi TV Stick's whole 384 MB heap allows in one piece - and
     * its tree several times that again. That was the "Failed to allocate a 268501000 byte
     * allocation" a viewer saw switching to a big second playlist. Here the response is read as it
     * arrives and only the entry being read is ever in memory, so the size of a catalogue costs
     * only the items kept from it.
     *
     * The top level is normally an array; some panel forks send an object keyed by id instead, and
     * its values are read the same way. Anything else - an error page, a bare word - is refused as
     * an unsupported response, as org.json refused it when the list was parsed whole.
     */
    private fun streamObjects(url: String, onItem: (JSONObject) -> Unit) {
        // Never log request URLs: the query contains account credentials.
        val request = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "The server returned HTTP ${response.code}. Please try again." }
                val body = response.body ?: throw IllegalArgumentException("The server returned an empty response.")
                val reader = java.io.PushbackReader(body.charStream(), 1)
                reader.read().let { first -> if (first != -1 && first != 0xFEFF) reader.unread(first) }
                android.util.JsonReader(reader).use { json ->
                    json.isLenient = true
                    when (json.peek()) {
                        android.util.JsonToken.BEGIN_ARRAY -> {
                            json.beginArray()
                            while (json.hasNext()) readEntry(json)?.let(onItem)
                            json.endArray()
                        }
                        android.util.JsonToken.BEGIN_OBJECT -> {
                            json.beginObject()
                            while (json.hasNext()) { json.nextName(); readEntry(json)?.let(onItem) }
                            json.endObject()
                        }
                        else -> throw IllegalArgumentException("This server returned an unsupported response.")
                    }
                }
            }
        } catch (error: IOException) {
            // Transport exception messages can contain the credential-bearing URL.
            throw IllegalArgumentException("Could not connect to the server. Check the address and your connection.")
        } catch (error: IllegalStateException) {
            throw IllegalArgumentException("This server returned an unsupported response.")
        }
    }

    /** One entry as a JSONObject, or null (skipped) when it is not an object. */
    private fun readEntry(json: android.util.JsonReader): JSONObject? {
        if (json.peek() != android.util.JsonToken.BEGIN_OBJECT) { json.skipValue(); return null }
        return readValue(json) as JSONObject
    }

    private fun readValue(json: android.util.JsonReader): Any? = when (json.peek()) {
        android.util.JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
            json.beginObject()
            while (json.hasNext()) obj.put(json.nextName(), readValue(json) ?: JSONObject.NULL)
            json.endObject()
        }
        android.util.JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            json.beginArray()
            while (json.hasNext()) array.put(readValue(json) ?: JSONObject.NULL)
            json.endArray()
        }
        // Numbers are kept as their text, exactly as JSONObject.optString would have given them.
        android.util.JsonToken.NUMBER, android.util.JsonToken.STRING -> json.nextString()
        android.util.JsonToken.BOOLEAN -> json.nextBoolean()
        android.util.JsonToken.NULL -> { json.nextNull(); null }
        else -> { json.skipValue(); null }
    }

    private fun liveItems(url: String, groups: Map<String, String>, server: String, input: PlaylistInput) = buildList {
        // Percent-encoding the account once outside the loop rather than twice per entry: this runs
        // over every channel the panel returns (tens of thousands on a full playlist), and the
        // result is identical every time.
        val streamPrefix = "$server/live/${encode(input.username)}/${encode(input.password)}/"
        streamObjects(url) { item ->
            val id = item.optString("stream_id")
            if (id.isBlank()) return@streamObjects
            add(PlaylistItem(
                item.optString("name", "Unnamed channel"),
                "$streamPrefix$id.ts",
                groups[item.optString("category_id")] ?: "Other",
                item.optText("stream_icon"),
                // stream_id is the provider's unique channel identity. EPG IDs
                // may be blank or shared by several streams and must not be
                // used for favorites or viewing history.
                id,
                MediaKind.LIVE
            ))
        }
    }

    private fun movieItems(url: String, groups: Map<String, String>, server: String, input: PlaylistInput) = buildList {
        // See liveItems: hoisted out of a loop that runs once per title in the whole VOD catalogue.
        val streamPrefix = "$server/movie/${encode(input.username)}/${encode(input.password)}/"
        streamObjects(url) { item ->
            val id = item.optString("stream_id")
            if (id.isBlank()) return@streamObjects
            val extension = item.optText("container_extension") ?: "mp4"
            add(PlaylistItem(
                item.optString("name", "Unnamed movie"),
                "$streamPrefix$id.$extension",
                groups[item.optString("category_id")] ?: "Other",
                // Field name for the poster varies a lot between Xtream panel forks - some put it
                // on stream_icon (like live channels), others only on cover/cover_big/movie_image
                // even at the list level (movieDetails' get_vod_info fallback already checks this
                // same set; the list endpoint needs the same breadth or most posters stay blank
                // until the details page is opened).
                firstText(item, "stream_icon", "cover", "cover_big", "movie_image"), id, MediaKind.MOVIE,
                description = item.optText("plot"),
                year = item.optText("year") ?: item.optText("releaseDate")?.take(4),
                rating = item.optText("rating"),
                duration = item.optText("duration"),
                addedEpochSeconds = epochSeconds(item, "added")
            ))
        }
    }

    private fun seriesItems(url: String, groups: Map<String, String>) = buildList {
        streamObjects(url) { item ->
            val id = item.optString("series_id")
            if (id.isBlank()) return@streamObjects
            add(PlaylistItem(
                item.optString("name", "Unnamed series"), "series://$id",
                groups[item.optString("category_id")] ?: "Other",
                firstText(item, "cover", "cover_big", "movie_image", "stream_icon"), id, MediaKind.SERIES,
                // Series have no "added" of their own on this endpoint; panels report when the run
                // last gained an episode instead, which is the same thing a viewer means by "new".
                addedEpochSeconds = epochSeconds(item, "last_modified", "added")
            ))
        }
    }

    /**
     * Reads a catalogue timestamp, in whichever of the two shapes panels use: epoch seconds as a
     * string, or occasionally milliseconds. Anything outside a plausible range is discarded rather
     * than trusted, so one panel's malformed field cannot park a title permanently at the top of
     * "recently added".
     */
    private fun epochSeconds(item: JSONObject, vararg keys: String): Long? {
        val raw = firstText(item, *keys)?.toLongOrNull() ?: return null
        val seconds = if (raw > 100_000_000_000L) raw / 1000L else raw
        return seconds.takeIf { it in 946_684_800L..4_102_444_800L }
    }

    private fun apiUrl(server: String, input: PlaylistInput, action: String?): String = buildString {
        append(server).append("/player_api.php?username=").append(encode(input.username))
        append("&password=").append(encode(input.password))
        if (action != null) append("&action=").append(action)
    }

    private fun firstText(objectValue: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { objectValue.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) }

    // org.json's optString() returns the literal string "null" (not a real null) when the JSON
    // value itself is null - a common way panels mark "no icon/plot/etc. for this item". Every
    // optString() read of provider data must be filtered through this (or firstText, its
    // multi-key sibling) instead of a plain isNotBlank() check, or a null poster/backdrop/plot
    // field silently becomes the four-character string "null" instead of Kotlin null.
    private fun JSONObject.optText(key: String): String? = firstText(this, key)

    private fun containsLatinText(value: String): Boolean = value.any { it in 'A'..'Z' || it in 'a'..'z' }

    private fun normalizeServerBase(value: String): String {
        val uri = URI(value)
        val scheme = if (uri.port == 80 && uri.scheme.equals("https", true)) "http" else uri.scheme.lowercase()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
        return "$scheme://${uri.host}$port$path"
    }

    private fun addressCandidates(value: String): List<String> {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Enter a playlist or server address." }
        val candidates = when {
            trimmed.startsWith("https://", true) && URI(trimmed).port == 80 ->
                listOf(trimmed.replaceFirst(Regex("^https", RegexOption.IGNORE_CASE), "http"))
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> listOf(trimmed)
            else -> listOf("http://$trimmed", "https://$trimmed")
        }
        candidates.forEach { require(URI(it).host != null) { "Enter a valid server or playlist address." } }
        return candidates
    }

    private fun download(url: String): String {
        // Let OkHttp supply its standard headers, gzip decoding and redirects.
        // Never log request URLs: the query contains account credentials.
        val request = Request.Builder().url(url).get().build()
        try {
            return httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "The server returned HTTP ${response.code}. Please try again."
                }
                val body = response.body
                    ?: throw IllegalArgumentException("The server returned an empty response.")
                body.charStream().use { reader ->
                    buildString {
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count == -1) break
                            require(length.toLong() + count <= 80_000_000L) {
                                "The provider response is too large to load safely."
                            }
                            append(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: IOException) {
            // Transport exception messages can contain the credential-bearing URL.
            throw IllegalArgumentException("Could not connect to the server. Check the address and your connection.")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private companion object {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", PROVIDER_USER_AGENT).build())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .trustModernRoots()
            .build()
    }
}
