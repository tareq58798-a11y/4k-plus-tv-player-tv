package com.fourkplus.tvplayer.data

object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val movieExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v")

    fun parse(name: String, content: String): LoadedPlaylist {
        val lines = content.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull()?.startsWith("#EXTM3U", ignoreCase = true) == true) {
            "This address did not return a valid M3U playlist."
        }

        val items = mutableListOf<PlaylistItem>()
        var metadata: String? = null
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> metadata = line
                line.startsWith("#") -> Unit
                metadata != null -> {
                    val info = metadata.orEmpty()
                    val attrs = attribute.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val title = info.substringAfterLast(',', attrs["tvg-name"].orEmpty()).trim()
                        .ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "Unnamed item" } }
                    val group = attrs["group-title"].orEmpty().ifBlank { "Other" }
                    items += PlaylistItem(
                        name = title,
                        streamUrl = line,
                        group = group,
                        logoUrl = attrs["tvg-logo"].takeUnless { it.isNullOrBlank() },
                        channelId = attrs["tvg-id"].takeUnless { it.isNullOrBlank() },
                        kind = detectKind(group, title, line)
                    )
                    metadata = null
                }
            }
        }
        require(items.isNotEmpty()) { "The playlist is valid but contains no playable items." }
        return LoadedPlaylist(name.trim(), items, items.map { it.group }.distinct())
    }

    private fun detectKind(group: String, title: String, url: String): MediaKind {
        val text = "$group $title".lowercase()
        return when {
            "series" in text || "episode" in text || Regex("s\\d{1,2}e\\d{1,3}").containsMatchIn(text) -> MediaKind.SERIES
            "movie" in text || "film" in text || movieExtensions.any { url.substringBefore('?').lowercase().endsWith(it) } -> MediaKind.MOVIE
            else -> MediaKind.LIVE
        }
    }
}
