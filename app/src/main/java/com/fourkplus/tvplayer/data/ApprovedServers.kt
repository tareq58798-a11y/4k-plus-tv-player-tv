package com.fourkplus.tvplayer.data

import java.net.URI

object ApprovedServers {
    // Shown in the login screen as Server 1 and Server 2, in this order - the chips are labelled
    // by position, so changing the order changes which number a viewer is told to pick.
    val addresses = listOf(
        "http://bag41135.wd.4kplus-tv-za.xyz/",
        "http://dtamadeus.com:80"
    )

    private val approvedHosts = setOf("bag41135.wd.4kplus-tv-za.xyz", "dtamadeus.com")

    fun allows(input: PlaylistInput): Boolean = runCatching {
        val uri = URI(input.address.trim())
        val approvedHost = uri.scheme.equals("http", true) &&
            uri.host?.lowercase() in approvedHosts &&
            uri.port in setOf(-1, 80) &&
            uri.rawUserInfo == null
        when (input.kind) {
            // Provider Login must be the bare server root: no path, query or fragment.
            PlaylistKind.PROVIDER_LOGIN -> approvedHost &&
                uri.path.orEmpty() in setOf("", "/") &&
                uri.rawQuery == null && uri.rawFragment == null
            // M3U URLs need their own path/query (e.g. get.php?...&type=m3u_plus) to point at
            // a real playlist, so only the host/scheme/port are restricted here.
            PlaylistKind.M3U_URL -> approvedHost
            // Never checked directly: PlaylistRepository resolves this kind via
            // DeviceActivationClient into a M3U_URL/PROVIDER_LOGIN input first.
            PlaylistKind.DEVICE_ACTIVATION -> false
        }
    }.getOrDefault(false)
}
