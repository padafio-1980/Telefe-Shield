package com.pfservices.telefeshield

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    companion object {
        private const val PAGE_URL = "https://mitelefe.com/telefe-en-vivo"
        private const val TOKEN_URL = "https://mitelefe.com/vidya/tokenize"
        private const val ORIGIN = "https://mitelefe.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private var player: ExoPlayer? = null
    private var retryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        buildUi()
        resolveAndPlay()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(64, 64, Gravity.CENTER))

        status = TextView(this).apply {
            text = "Connecting to Telefe…"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = 70 })
        setContentView(root)
    }

    private fun resolveAndPlay() {
        showStatus("Connecting to Telefe…", true)
        val request = Request.Builder()
            .url(PAGE_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = fail("Could not load Telefe. Check your VPN/network.")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return fail("Telefe page returned HTTP ${it.code}.")
                    val html = it.body?.string().orEmpty()
                    val raw = Regex("data-player-url\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)
                    if (raw.isNullOrBlank()) return fail("Could not find Telefe's live player URL.")
                    val streamUrl = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                    tokenize(streamUrl)
                }
            }
        })
    }

    private fun tokenize(streamUrl: String) {
        val body = JSONObject().put("url", streamUrl).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN)
            .header("Referer", PAGE_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = fail("Could not request the live stream token.")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string()?.trim().orEmpty()
                    if (!it.isSuccessful) return fail("Telefe token service returned HTTP ${it.code}.")
                    val hls = try {
                        if (text.startsWith("{")) JSONObject(text).optString("url")
                        else text.trim('"')
                    } catch (_: Exception) { "" }
                    if (!hls.startsWith("http") || !hls.contains(".m3u8")) return fail("Telefe returned an unexpected stream response.")
                    runOnUiThread { startPlayer(hls) }
                }
            }
        })
    }

    private fun startPlayer(hlsUrl: String) {
        player?.release()
        val headers = mapOf(
            "Origin" to ORIGIN,
            "Referer" to PAGE_URL,
            "User-Agent" to USER_AGENT
        )
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.Builder()
            .setUri(hlsUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val source = HlsMediaSource.Factory(dataSource).createMediaSource(mediaItem)
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        retryCount = 0
                        showStatus("", false)
                    }
                    if (state == Player.STATE_ENDED) retryLater()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    retryLater()
                }
            })
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun retryLater() {
        if (isFinishing || isDestroyed) return
        retryCount++
        player?.stop()
        showStatus("Reconnecting…", true)
        root.postDelayed({ if (!isFinishing && !isDestroyed) resolveAndPlay() }, (1500L * retryCount.coerceAtMost(4)))
    }

    private fun fail(message: String) {
        runOnUiThread {
            showStatus("$message\n\nPress OK to retry.", false)
        }
    }

    private fun showStatus(message: String, loading: Boolean) {
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (player == null || player?.playerError != null || status.visibility == View.VISIBLE) {
                retryCount = 0
                resolveAndPlay()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
