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
