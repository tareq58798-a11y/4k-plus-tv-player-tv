package com.fourkplus.tvplayer.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Looks up the real trailer video ID for [title] (optionally narrowed by [year]) via the
 *  activation backend's /api/trailer endpoint - a thin proxy over the YouTube Data API that keeps
 *  the API key server-side. Lets the app's Trailer button jump straight into the correct video
 *  instead of a YouTube search-results page the user has to pick through themselves. Returns null
 *  on any failure (no network, no API key configured on the backend, nothing matched) so callers
 *  fall back to the old search-page behavior. */
internal object TrailerSearchClient {
    private const val BASE_URL = "https://fourk-plus-tv-player.onrender.com/api/trailer"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun findTrailerVideoId(title: String, year: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("title", title)
                .apply { if (!year.isNullOrBlank()) addQueryParameter("year", year) }
                .build()
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                JSONObject(body).optString("videoId").takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }
}
