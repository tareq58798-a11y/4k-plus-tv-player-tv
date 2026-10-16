package com.fourkplus.tvplayer.data

import android.test.InstrumentationTestCase
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Local HTTP fixtures only; never uses real device identity or provider credentials. */
class PlaylistLoadingTest : InstrumentationTestCase() {
    private class Provider(val failMovies: Boolean = false, val pending: Boolean = false) : java.io.Closeable {
        private val socket = ServerSocket(0)
        val address = "http://127.0.0.1:${socket.localPort}"
        val actions = CopyOnWriteArrayList<String>()
        val userAgents = CopyOnWriteArrayList<String>()
        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val peer = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    peer.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        var length = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.startsWith("User-Agent:", true)) userAgents += header.substringAfter(':').trim()
                        }
                        repeat(length) { reader.read() }
                        val action = request.substringAfter("action=", "auth").substringBefore(' ').substringBefore('&')
                        actions += action
                        if (action == "get_vod_streams" || action == "get_series") Thread.sleep(150)
                        val body = when {
                            request.contains("/playlist.m3u") -> "#EXTM3U\n#EXTINF:-1 group-title=\"Test\",Test live\nhttp://example.invalid/live.ts\n"
                            request.contains("/activate") -> if (pending) """{"status":"pending"}""" else """{"status":"assigned","type":"xtream","name":"Test","server":"$address","username":"fixture","password":"fixture"}"""
                            action == "auth" -> """{"user_info":{"auth":1,"status":"Active"}}"""
                            action.endsWith("categories") -> """[{"category_id":"1","category_name":"Test"}]"""
                            action == "get_live_streams" -> """[{"stream_id":1,"name":"Live","category_id":"1"}]"""
                            action == "get_vod_streams" -> if (failMovies) "invalid-json" else """[{"stream_id":2,"name":"Movie","category_id":"1"}]"""
                            else -> """[{"series_id":3,"name":"Series","category_id":"1"}]"""
                        }
                        val bytes = body.toByteArray()
                        it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        it.getOutputStream().write(bytes)
                    }
                }
            }
        }
        fun source() = PlaylistInput("Test", PlaylistKind.PROVIDER_LOGIN, address, "fixture", "fixture")
        override fun close() { socket.close(); acceptor.join(1000) }
    }

    fun testLiveArrivesBeforeOnDemandRequests() = runBlocking {
        Provider().use { server ->
            val partials = mutableListOf<LoadedPlaylist>()
            val complete = XtreamProviderClient().loadProgressively(server.source()) {
                if (partials.isEmpty()) {
                    assertEquals(1, it.liveCount)
                    assertEquals(0, it.movieCount)
                    assertFalse(server.actions.contains("get_vod_streams"))
                    assertFalse(server.actions.contains("get_series"))
                }
                partials += it
            }
            assertEquals(3, complete.items.size)
            assertEquals(1, complete.seriesCount)
            assertTrue(partials.isNotEmpty())
        }
    }

    /** Panels that refuse OkHttp's default name (HTTP 513 from one) are sent the player's name. */
    fun testLoginAndListsSendThePlayersName() = runBlocking {
        Provider().use { server ->
            XtreamProviderClient().loadProgressively(server.source()) { }
            assertTrue(server.userAgents.isNotEmpty())
            assertTrue(server.userAgents.toString(), server.userAgents.all { it == PROVIDER_USER_AGENT })
        }
    }

    fun testMovieFailureKeepsLiveAndSuccessfulSeries() = runBlocking {
        Provider(failMovies = true).use { server ->
            var last: LoadedPlaylist? = null
            val result = runCatching { XtreamProviderClient().loadProgressively(server.source()) { last = it } }
            assertTrue(result.isFailure)
            assertEquals(1, last!!.liveCount)
            assertEquals(1, last!!.seriesCount)
        }
    }

    fun testCancelAfterLiveDoesNotFetchOnDemand() = runBlocking {
        Provider().use { server ->
            val result = runCatching {
                XtreamProviderClient().loadProgressively(server.source()) { throw kotlinx.coroutines.CancellationException("fixture") }
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(server.actions.contains("get_vod_streams"))
        }
    }

    fun testActivationPendingAndAssigned() = runBlocking {
        val input = PlaylistInput("Test", PlaylistKind.DEVICE_ACTIVATION, "", "00:00:00:00:00:00", "fixture")
        Provider(pending = true).use {
            assertTrue(runCatching { DeviceActivationClient.resolve(input, "${it.address}/activate") }.exceptionOrNull() is ActivationPendingException)
        }
        Provider().use {
            val resolved = DeviceActivationClient.resolve(input, "${it.address}/activate")
            assertEquals(PlaylistKind.PROVIDER_LOGIN, resolved.kind)
            assertEquals(it.address, resolved.address)
        }
    }
    fun testM3uStillLoadsAsOneCompletePlaylist() = runBlocking {
        Provider().use { server ->
            var partialCalled = false
            val loaded = XtreamProviderClient().loadProgressively(
                PlaylistInput("Test", PlaylistKind.M3U_URL, "${server.address}/playlist.m3u")
            ) { partialCalled = true }
            assertEquals(1, loaded.items.size)
            assertFalse(partialCalled)
        }
    }

    /**
     * A film list far larger than the whole heap could hold as text loads, entry by entry.
     *
     * A Mi TV Stick switching to a big second playlist failed with "Failed to allocate a 268501000
     * byte allocation": the list was read whole into one String before parsing. This provider
     * streams a film list of about 100 million characters - more than that String could ever hold
     * here - written as it is produced, so the fixture itself never holds it either. Each entry
     * carries a long field the app ignores, as real panels do, so the items kept stay small.
     */
    fun testHugeListStreamsWithoutHoldingItWhole() = runBlocking {
        val entries = 120_000
        val filler = "x".repeat(800)
        val socket = ServerSocket(0)
        val address = "http://127.0.0.1:${socket.localPort}"
        val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val peer = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    peer.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val action = request.substringAfter("action=", "auth").substringBefore(' ').substringBefore('&')
                        val out = java.io.BufferedOutputStream(it.getOutputStream(), 64 * 1024)
                        // No Content-Length: the body ends when the connection closes, as a large
                        // gzip-encoded panel response effectively does.
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                        when {
                            action == "auth" -> out.write("""{"user_info":{"auth":1,"status":"Active"}}""".toByteArray())
                            action.endsWith("categories") -> out.write("""[{"category_id":"1","category_name":"Test"}]""".toByteArray())
                            action == "get_live_streams" -> out.write("""[{"stream_id":1,"name":"Live","category_id":"1"}]""".toByteArray())
                            action == "get_vod_streams" -> {
                                out.write('['.code)
                                for (index in 1..entries) {
                                    if (index > 1) out.write(','.code)
                                    out.write("""{"stream_id":$index,"name":"Film $index","category_id":"1","container_extension":"mp4","unused_text":"$filler"}""".toByteArray())
                                }
                                out.write(']'.code)
                            }
                            else -> out.write("[]".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }
        try {
            val source = PlaylistInput("Huge", PlaylistKind.PROVIDER_LOGIN, address, "fixture", "fixture")
            val loaded = XtreamProviderClient().loadProgressively(source) { }
            assertEquals(1, loaded.liveCount)
            assertEquals(entries, loaded.movieCount)
            assertEquals("Film $entries", loaded.items.last { it.kind == MediaKind.MOVIE }.name)
            assertTrue("about 100 million characters were sent", entries.toLong() * filler.length > 90_000_000L)
        } finally { socket.close(); acceptor.join(1000) }
    }

    fun testCacheRoundTripPreservesCompleteCatalogue() = runBlocking {
        val parent = instrumentation.targetContext
        val folder = java.io.File(parent.cacheDir, "playlist-regression-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(parent) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = folder
        }
        try {
            val source = PlaylistInput("Fixture", PlaylistKind.PROVIDER_LOGIN, "http://example.invalid")
            val items = MediaKind.values().mapIndexed { index, kind ->
                PlaylistItem("Item $index", "http://example.invalid/$index", "Group", null, "$index", kind)
            }
            val complete = LoadedPlaylist("Fixture", items, listOf("Group"), "Active", 123456789L)
            val cache = PlaylistCacheStore(isolated)
            cache.save(source, complete)
            assertEquals(complete, cache.load(source))
            cache.deleteFor(source)
            assertNull(cache.load(source))
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

}
