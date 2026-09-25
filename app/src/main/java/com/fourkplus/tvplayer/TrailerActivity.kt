package com.fourkplus.tvplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * A trailer, played inside the app.
 *
 * It used to go to the YouTube app, and once YouTube has the screen Back is YouTube's: it steps
 * back through YouTube's own screens before returning here. Played in its own screen over the
 * film's or series' page, one press of Back ends the trailer and puts the viewer back on the page.
 *
 * Only the provider's own trailer is played - the id comes from trailerVideoId, and nothing is
 * ever searched for. YouTube's embedded player is the only way its videos may be shown in another
 * app, and it will not play in a page with no web address of its own (its error 153), so the player
 * page lives on the activation server (server/trailerPage.js), given the video id and nothing else.
 * The same page the Samsung app uses (web/src/ui/trailerPlayer.ts), with the same keys.
 *
 * The page reports what the player is doing through the FourKPlusTrailer bridge, and takes toggle
 * and seek as a postMessage to itself. The WebView never takes focus, so the remote's keys all come
 * here first. If YouTube refuses the video, or nothing has started after a while - which is also
 * what a sleeping server looks like - the viewer is told, and OK or a tap opens the YouTube app.
 */
class TrailerActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var videoId: String
    private var started = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { fail() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (id == null || !VIDEO_ID.matches(id)) {
            finish()
            return
        }
        videoId = id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            // The remote stays with this screen: see dispatchKeyEvent.
            isFocusable = false
            isFocusableInTouchMode = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The trailer starts by itself; the viewer has already asked for it by pressing Trailer.
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "FourKPlusTrailer")
            loadUrl("$TRAILER_PAGE?v=$videoId")
        }
        val density = resources.displayMetrics.density
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xBF000000.toInt())
            text = getString(R.string.trailer_loading)
        }
        // A phone has no OK button: a tap does what OK does. It sits over the WebView so a touch
        // never reaches YouTube's page, whose controls are switched off.
        val touch = View(this).apply { setOnClickListener { press() } }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(match, match))
            addView(touch, FrameLayout.LayoutParams(match, match))
            addView(
                status,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)
        handler.postDelayed(giveUp, START_TIMEOUT_MS)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            runOnUiThread { onEvent(json) }
        }
    }

    private fun onEvent(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (event.optString("type")) {
            "error" -> fail()
            "state" -> when (event.optString("state")) {
                "playing" -> {
                    started = true
                    failed = false
                    handler.removeCallbacks(giveUp)
                    status.visibility = View.GONE
                }
                "ended" -> finish()
            }
        }
    }

    private fun fail() {
        if (started || isFinishing) return
        failed = true
        val how = if (isTvDevice()) R.string.trailer_open_youtube_ok else R.string.trailer_open_youtube_tap
        status.text = getString(R.string.trailer_failed_here) + "\n" + getString(how)
        status.visibility = View.VISIBLE
    }

    /** OK, Play/Pause or a tap: pause and resume, or, once it has failed, the YouTube app. */
    private fun press() {
        if (failed) {
            finish()
            openTrailerInYouTube(this, videoId)
        } else {
            send("toggle")
        }
    }

    private fun send(action: String, seconds: Int = 0) {
        web.evaluateJavascript(
            "window.postMessage({target:'fourkplus-trailer',action:'$action',seconds:$seconds},'*')",
            null
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            // One press, straight back to the page.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> send("seek", -SEEK_SECONDS)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> send("seek", SEEK_SECONDS)
            // Once per press: a held OK must not pause and resume over and over.
            else -> if (event.repeatCount == 0) press()
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        if (::web.isInitialized) {
            // Emptied first so the video stops at once.
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        private const val TRAILER_PAGE = "https://fourk-plus-tv-player.onrender.com/trailer"
        private const val START_TIMEOUT_MS = 25_000L
        private const val SEEK_SECONDS = 10
        private val VIDEO_ID = Regex("[\\w-]{11}")
    }
}
