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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : Activity() {

    companion object {
        private const val PAGE_URL =
            "https://mitelefe.com/telefe-en-vivo"

        private const val TOKEN_URL =
            "https://mitelefe.com/vidya/tokenize"

        private const val ORIGIN =
            "https://mitelefe.com"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) " +
            "AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }

    /*
     * NORMAL HTTPS CLIENT
     *
     * Dailymotion continues to use normal certificate
     * and hostname verification.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /*
     * TELEFE-ONLY HTTPS CLIENT
     *
     * The SHIELD does not accept the certificate chain
     * currently presented by mitelefe.com.
     *
     * Certificate-chain verification is bypassed here,
     * but hostname access is restricted to mitelefe.com
     * and its subdomains.
     *
     * Dailymotion DOES NOT use this client.
     */
    private val telefeHttp: OkHttpClient by lazy {

        val trustAll =
            object : X509TrustManager {

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun getAcceptedIssuers():
                    Array<X509Certificate> =
                    emptyArray()
            }

        val sslContext =
            SSLContext.getInstance("TLS")

        sslContext.init(
            null,
            arrayOf<TrustManager>(trustAll),
            SecureRandom()
        )

        OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustAll
            )
            .hostnameVerifier { hostname, _ ->

                hostname.equals(
                    "mitelefe.com",
                    ignoreCase = true
                ) ||
                hostname.endsWith(
                    ".mitelefe.com",
                    ignoreCase = true
                )
            }
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                20,
                TimeUnit.SECONDS
            )
            .followRedirects(true)
            .build()
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar

    private var player: ExoPlayer? = null
    private var retryCount = 0

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        buildUi()
        resolveAndPlay()
    }

    private fun buildUi() {

        root =
            FrameLayout(this).apply {
                setBackgroundColor(
                    0xFF000000.toInt()
                )
            }

        playerView =
            PlayerView(this).apply {

                useController = true

                controllerShowTimeoutMs =
                    3500

                setShowBuffering(
                    PlayerView.SHOW_BUFFERING_ALWAYS
                )
            }

        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        spinner =
            ProgressBar(this)

        root.addView(
            spinner,
            FrameLayout.LayoutParams(
                64,
                64,
                Gravity.CENTER
            )
        )

        status =
            TextView(this).apply {

                text =
                    "Connecting to Telefe…"

                setTextColor(
                    0xFFFFFFFF.toInt()
                )

                textSize =
                    18f

                gravity =
                    Gravity.CENTER

                setPadding(
                    40,
                    30,
                    40,
                    30
                )
            }

        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        setContentView(root)
    }

    /*
     * STEP 1
     *
     * Load the Telefe live page.
     *
     * This request uses telefeHttp because the
     * SHIELD rejects Telefe's certificate chain.
     */
    private fun resolveAndPlay() {

        showStatus(
            "Connecting to Telefe…",
            true
        )

        val request =
            Request.Builder()
                .url(PAGE_URL)
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .build()

        telefeHttp
            .newCall(request)
            .enqueue(

                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        fail(
                            "Could not load Telefe.\n\n${e.message}"
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        response.use {

                            if (!it.isSuccessful) {

                                return fail(
                                    "Telefe page returned HTTP ${it.code}."
                                )
                            }

                            val html =
                                it.body
                                    ?.string()
                                    .orEmpty()

                            val raw =
                                Regex(
                                    "data-player-url\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']",
                                    RegexOption.IGNORE_CASE
                                )
                                    .find(html)
                                    ?.groupValues
                                    ?.getOrNull(1)

                            if (
                                raw.isNullOrBlank()
                            ) {

                                return fail(
                                    "Could not find Telefe's live player URL."
                                )
                            }

                            val streamUrl =
                                Html.fromHtml(
                                    raw,
                                    Html.FROM_HTML_MODE_LEGACY
                                ).toString()

                            if (
                                streamUrl.contains(
                                    "dailymotion.com",
                                    ignoreCase = true
                                )
                            ) {

                                resolveDailymotion(
                                    streamUrl
                                )

                            } else {

                                tokenizeTelefe(
                                    streamUrl
                                )
                            }
                        }
                    }
                }
            )
    }

    /*
     * STEP 2
     *
     * Resolve Dailymotion.
     *
     * IMPORTANT:
     * This uses the NORMAL HTTPS client.
     */
    private fun resolveDailymotion(
        dailymotionUrl: String
    ) {

        showStatus(
            "Dailymotion detected.\nResolving live stream…",
            true
        )

        val videoId =
            Regex(
                """dailymotion\.com/(?:embed/)?(?:video|live)/([^_?/#]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(dailymotionUrl)
                ?.groupValues
                ?.getOrNull(1)

        if (
            videoId.isNullOrBlank()
        ) {

            return fail(
                "Could not determine the Dailymotion video ID.\n\n$dailymotionUrl"
            )
        }

        /*
         * This embedder parameter is required for
         * Telefe's Dailymotion playback.
         */
        val metadataUrl =
            "https://www.dailymotion.com/player/metadata/video/$videoId"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "embedder",
                    PAGE_URL
                )
                .build()

        val request =
            Request.Builder()
                .url(metadataUrl)
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .header(
                    "Referer",
                    "https://www.dailymotion.com/"
                )
                .header(
                    "Accept",
                    "*/*"
                )
                .header(
                    "x-cache-internal",
                    "true"
                )
                .header(
                    "x-cache-max-age",
                    "-1"
                )
                .header(
                    "Cookie",
                    "family_filter=off; ff=off"
                )
                .build()

        /*
         * NORMAL HTTPS validation here.
         */
        http.newCall(request)
            .enqueue(

                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        fail(
                            "Dailymotion request failed.\n\n${e.message}"
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        response.use {

                            val text =
                                it.body
                                    ?.string()
                                    .orEmpty()

                            if (
                                !it.isSuccessful
                            ) {

                                return fail(
                                    "Dailymotion HTTP ${it.code}\n\n$text"
                                )
                            }

                            try {

                                val json =
                                    JSONObject(text)

                                if (
                                    json.has("error")
                                ) {

                                    val error =
                                        json.optJSONObject(
                                            "error"
                                        )

                                    val message =
                                        error?.optString(
                                            "message"
                                        )
                                            ?: error?.optString(
                                                "raw_message"
                                            )
                                            ?: error?.toString()
                                            ?: "Unknown Dailymotion error"

                                    return fail(
                                        "Dailymotion returned an error:\n\n$message"
                                    )
                                }

                                val qualities =
                                    json.optJSONObject(
                                        "qualities"
                                    )
                                        ?: return fail(
                                            "Dailymotion returned no video qualities."
                                        )

                                var hlsUrl:
                                    String? =
                                    null

                                /*
                                 * First try automatic HLS.
                                 */
                                val auto =
                                    qualities.optJSONArray(
                                        "auto"
                                    )

                                if (
                                    auto != null
                                ) {

                                    for (
                                        i in 0 until auto.length()
                                    ) {

                                        val source =
                                            auto.optJSONObject(
                                                i
                                            )
                                                ?: continue

                                        val url =
                                            source.optString(
                                                "url"
                                            )

                                        val type =
                                            source.optString(
                                                "type"
                                            )

                                        if (
                                            url.startsWith(
                                                "http"
                                            ) &&
                                            (
                                                url.contains(
                                                    ".m3u8",
                                                    ignoreCase = true
                                                ) ||
                                                type.contains(
                                                    "mpegurl",
                                                    ignoreCase = true
                                                )
                                            )
                                        ) {

                                            hlsUrl =
                                                url

                                            break
                                        }
                                    }
                                }

                                /*
                                 * Search all other qualities if
                                 * automatic did not contain HLS.
                                 */
                                if (
                                    hlsUrl == null
                                ) {

                                    val keys =
                                        qualities.keys()

                                    while (
                                        keys.hasNext() &&
                                        hlsUrl == null
                                    ) {

                                        val key =
                                            keys.next()

                                        val array =
                                            qualities.optJSONArray(
                                                key
                                            )
                                                ?: continue

                                        for (
                                            i in 0 until array.length()
                                        ) {

                                            val source =
                                                array.optJSONObject(
                                                    i
                                                )
                                                    ?: continue

                                            val url =
                                                source.optString(
                                                    "url"
                                                )

                                            val type =
                                                source.optString(
                                                    "type"
                                                )

                                            if (
                                                url.startsWith(
                                                    "http"
                                                ) &&
                                                (
                                                    url.contains(
                                                        ".m3u8",
                                                        ignoreCase = true
                                                    ) ||
                                                    type.contains(
                                                        "mpegurl",
                                                        ignoreCase = true
                                                    )
                                                )
                                            ) {

                                                hlsUrl =
                                                    url

                                                break
                                            }
                                        }
                                    }
                                }

                                if (
                                    hlsUrl == null
                                ) {

                                    return fail(
                                        "Dailymotion metadata loaded, but no HLS stream was found."
                                    )
                                }

                                val finalUrl =
                                    hlsUrl

                                runOnUiThread {

                                    startPlayer(
                                        finalUrl,
                                        true
                                    )
                                }

                            } catch (
                                e: Exception
                            ) {

                                fail(
                                    "Could not parse Dailymotion metadata.\n\n" +
                                        "${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    }
                }
            )
    }

    /*
     * Telefe's non-Dailymotion fallback.
     *
     * This is also mitelefe.com, so it uses the
     * Telefe-only HTTPS client.
     */
    private fun tokenizeTelefe(
        streamUrl: String
    ) {

        showStatus(
            "Requesting Telefe stream…",
            true
        )

        val body =
            JSONObject()
                .put(
                    "url",
                    streamUrl
                )
                .toString()
                .toRequestBody(
                    "application/json"
                        .toMediaType()
                )

        val request =
            Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .header(
                    "Content-Type",
                    "application/json"
                )
                .header(
                    "Origin",
                    ORIGIN
                )
                .header(
                    "Referer",
                    PAGE_URL
                )
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .build()

        telefeHttp
            .newCall(request)
            .enqueue(

                object : Callback {

                    override fun onFailure(
                        call: Call,
                        e: IOException
                    ) {

                        fail(
                            "Could not request the Telefe stream token.\n\n${e.message}"
                        )
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {

                        response.use {

                            val text =
                                it.body
                                    ?.string()
                                    ?.trim()
                                    .orEmpty()

                            if (
                                !it.isSuccessful
                            ) {

                                return fail(
                                    "Telefe token HTTP ${it.code}\n\n$text"
                                )
                            }

                            val hls =
                                try {

                                    if (
                                        text.startsWith(
                                            "{"
                                        )
                                    ) {

                                        JSONObject(
                                            text
                                        )
                                            .optString(
                                                "url"
                                            )

                                    } else {

                                        text.trim(
                                            '"'
                                        )
                                    }

                                } catch (
                                    _: Exception
                                ) {

                                    ""
                                }

                            if (
                                !hls.startsWith(
                                    "http"
                                ) ||
                                !hls.contains(
                                    ".m3u8",
                                    ignoreCase = true
                                )
                            ) {

                                return fail(
                                    "Telefe returned an unexpected stream response."
                                )
                            }

                            runOnUiThread {

                                startPlayer(
                                    hls,
                                    false
                                )
                            }
                        }
                    }
                }
            )
    }

    private fun startPlayer(
        hlsUrl: String,
        dailymotion: Boolean
    ) {

        player?.release()

        val headers =
            if (dailymotion) {

                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.dailymotion.com/"
                )

            } else {

                mapOf(
                    "Origin" to ORIGIN,
                    "Referer" to PAGE_URL,
                    "User-Agent" to USER_AGENT
                )
            }

        val dataSource =
            DefaultHttpDataSource.Factory()
                .setUserAgent(
                    USER_AGENT
                )
                .setDefaultRequestProperties(
                    headers
                )
                .setAllowCrossProtocolRedirects(
                    true
                )

        val mediaItem =
            MediaItem.Builder()
                .setUri(
                    hlsUrl
                )
                .setMimeType(
                    MimeTypes.APPLICATION_M3U8
                )
                .build()

        val source =
            HlsMediaSource.Factory(
                dataSource
            )
                .createMediaSource(
                    mediaItem
                )

        player =
            ExoPlayer.Builder(this)
                .build()
                .also { exo ->

                    playerView.player =
                        exo

                    exo.addListener(

                        object :
                            Player.Listener {

                            override fun onPlaybackStateChanged(
                                state: Int
                            ) {

                                if (
                                    state ==
                                    Player.STATE_READY
                                ) {

                                    retryCount =
                                        0

                                    showStatus(
                                        "",
                                        false
                                    )
                                }

                                if (
                                    state ==
                                    Player.STATE_ENDED
                                ) {

                                    retryLater()
                                }
                            }

                            override fun onPlayerError(
                                error: PlaybackException
                            ) {

                                fail(
                                    "Player error:\n\n" +
                                        "${error.errorCodeName}\n" +
                                        "${error.message}"
                                )
                            }
                        }
                    )

                    exo.setMediaSource(
                        source
                    )

                    exo.prepare()

                    exo.playWhenReady =
                        true
                }
    }

    private fun retryLater() {

        if (
            isFinishing ||
            isDestroyed
        ) {
            return
        }

        retryCount++

        player?.stop()

        showStatus(
            "Reconnecting…",
            true
        )

        root.postDelayed(
            {

                if (
                    !isFinishing &&
                    !isDestroyed
                ) {

                    resolveAndPlay()
                }
            },
            1500L *
                retryCount.coerceAtMost(
                    4
                )
        )
    }

    private fun fail(
        message: String
    ) {

        runOnUiThread {

            showStatus(
                "$message\n\nPress OK to retry.",
                false
            )
        }
    }

    private fun showStatus(
        message: String,
        loading: Boolean
    ) {

        runOnUiThread {

            status.text =
                message

            status.visibility =
                if (
                    message.isBlank()
                ) {

                    View.GONE

                } else {

                    View.VISIBLE
                }

            spinner.visibility =
                if (loading) {

                    View.VISIBLE

                } else {

                    View.GONE
                }
        }
    }

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        if (
            event.action ==
            KeyEvent.ACTION_DOWN &&
            (
                event.keyCode ==
                KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode ==
                KeyEvent.KEYCODE_ENTER
            )
        ) {

            if (
                player == null ||
                player?.playerError != null ||
                status.visibility ==
                View.VISIBLE
            ) {

                retryCount =
                    0

                resolveAndPlay()

                return true
            }
        }

        return super.dispatchKeyEvent(
            event
        )
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

        playerView.player =
            null

        player?.release()

        player =
            null

        super.onDestroy()
    }
}