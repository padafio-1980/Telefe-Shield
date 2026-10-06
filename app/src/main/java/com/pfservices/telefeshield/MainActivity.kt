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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
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

    enum class QualityMode(
        val label: String
    ) {
        HD_1080("1080p"),
        AUTO("Auto"),
        HD_720("720p")
    }

    /*
     * 1080p is the default.
     */
    private var qualityMode =
        QualityMode.HD_1080

    /*
     * NORMAL HTTPS CLIENT.
     * Dailymotion uses normal certificate validation.
     */
    private val http =
        OkHttpClient.Builder()
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

    /*
     * TELEFE-ONLY HTTPS CLIENT.
     *
     * This preserves the working SHIELD certificate
     * workaround. Dailymotion does NOT use this client.
     */
    private val telefeHttp:
        OkHttpClient by lazy {

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
            arrayOf<TrustManager>(
                trustAll
            ),
            SecureRandom()
        )

        OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustAll
            )
            .hostnameVerifier {
                    hostname,
                    _ ->

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

    private lateinit var root:
        FrameLayout

    private lateinit var playerView:
        PlayerView

    private lateinit var status:
        TextView

    private lateinit var spinner:
        ProgressBar

    private lateinit var qualityOverlay:
        TextView

    private var player:
        ExoPlayer? = null

    private var retryCount =
        0

    private var centerDownTime =
        0L

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

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

                useController =
                    true

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

        /*
         * Quality indicator.
         */
        qualityOverlay =
            TextView(this).apply {

                text =
                    ""

                setTextColor(
                    0xFFFFFFFF.toInt()
                )

                setBackgroundColor(
                    0xCC000000.toInt()
                )

                textSize =
                    20f

                gravity =
                    Gravity.CENTER

                setPadding(
                    28,
                    16,
                    28,
                    16
                )

                visibility =
                    View.GONE
            }

        val qualityParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.TOP or
                        Gravity.END

                topMargin =
                    40

                marginEnd =
                    40
            }

        root.addView(
            qualityOverlay,
            qualityParams
        )

        setContentView(root)
    }

    private fun resolveAndPlay() {

        showStatus(
            "Connecting to Telefe…",
            true
        )

        val request =
            Request.Builder()
                .url(
                    PAGE_URL
                )
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .build()

        telefeHttp
            .newCall(
                request
            )
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

                            if (
                                !it.isSuccessful
                            ) {

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
                                    .find(
                                        html
                                    )
                                    ?.groupValues
                                    ?.getOrNull(
                                        1
                                    )

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
                                )
                                    .toString()

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
                .find(
                    dailymotionUrl
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )

        if (
            videoId.isNullOrBlank()
        ) {

            return fail(
                "Could not determine the Dailymotion video ID.\n\n$dailymotionUrl"
            )
        }

        /*
         * KEEP THIS:
         * Telefe requires the embedder parameter.
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
                .url(
                    metadataUrl
                )
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
         * Dailymotion stays on normal HTTPS.
         */
        http.newCall(
            request
        )
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
                                    JSONObject(
                                        text
                                    )

                                if (
                                    json.has(
                                        "error"
                                    )
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

                                /*
                                 * We deliberately use Dailymotion's
                                 * AUTO/master HLS playlist.
                                 *
                                 * ExoPlayer then controls whether it
                                 * can use 1080, Auto or max 720.
                                 */
                                var hlsUrl:
                                    String? =
                                    findHlsInQuality(
                                        qualities,
                                        "auto"
                                    )

                                /*
                                 * Fallback if Dailymotion does not
                                 * provide an auto/master playlist.
                                 */
                                if (
                                    hlsUrl == null
                                ) {

                                    val preferred =
                                        listOf(
                                            "1080",
                                            "720",
                                            "480",
                                            "380",
                                            "240",
                                            "144"
                                        )

                                    for (
                                        quality in preferred
                                    ) {

                                        hlsUrl =
                                            findHlsInQuality(
                                                qualities,
                                                quality
                                            )

                                        if (
                                            hlsUrl != null
                                        ) {
                                            break
                                        }
                                    }
                                }

                                /*
                                 * Last-resort search through whatever
                                 * quality keys Dailymotion returned.
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

                                        hlsUrl =
                                            findHlsInQuality(
                                                qualities,
                                                keys.next()
                                            )
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

    private fun findHlsInQuality(
        qualities: JSONObject,
        quality: String
    ): String? {

        val array =
            qualities.optJSONArray(
                quality
            )
                ?: return null

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

                return url
            }
        }

        return null
    }

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
                .url(
                    TOKEN_URL
                )
                .post(
                    body
                )
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
            .newCall(
                request
            )
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
            if (
                dailymotion
            ) {

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
            ExoPlayer.Builder(
                this
            )
                .build()
                .also { exo ->

                    playerView.player =
                        exo

                    /*
                     * Apply 1080p default BEFORE playback starts.
                     */
                    applyQuality(
                        exo,
                        qualityMode
                    )

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

                                    showQualityOverlay()
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

    /*
     * QUALITY CONTROL
     *
     * 1080p = adaptive playback, maximum 1080p.
     * Auto  = unrestricted adaptive playback.
     * 720p  = adaptive playback, maximum 720p.
     */
    private fun applyQuality(
        exo: ExoPlayer,
        mode: QualityMode
    ) {

        val builder =
            exo.trackSelectionParameters
                .buildUpon()

        when (
            mode
        ) {

            QualityMode.HD_1080 -> {

                builder.setMaxVideoSize(
                    Int.MAX_VALUE,
                    1080
                )
            }

            QualityMode.AUTO -> {

                builder.setMaxVideoSize(
                    Int.MAX_VALUE,
                    Int.MAX_VALUE
                )
            }

            QualityMode.HD_720 -> {

                builder.setMaxVideoSize(
                    Int.MAX_VALUE,
                    720
                )
            }
        }

        exo.trackSelectionParameters =
            builder.build()
    }

    private fun cycleQuality() {

        qualityMode =
            when (
                qualityMode
            ) {

                QualityMode.HD_1080 ->
                    QualityMode.AUTO

                QualityMode.AUTO ->
                    QualityMode.HD_720

                QualityMode.HD_720 ->
                    QualityMode.HD_1080
            }

        player?.let {

            applyQuality(
                it,
                qualityMode
            )
        }

        showQualityOverlay()
    }

    private fun showQualityOverlay() {

        runOnUiThread {

            qualityOverlay.text =
                "Quality: ${qualityMode.label}"

            qualityOverlay.visibility =
                View.VISIBLE

            qualityOverlay.removeCallbacks(
                hideQualityOverlay
            )

            qualityOverlay.postDelayed(
                hideQualityOverlay,
                2200
            )
        }
    }

    private val hideQualityOverlay =
        Runnable {

            qualityOverlay.visibility =
                View.GONE
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
                if (
                    loading
                ) {

                    View.VISIBLE

                } else {

                    View.GONE
                }
        }
    }

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        /*
         * MENU button cycles:
         *
         * 1080p -> Auto -> 720p -> 1080p
         */
        if (
            event.action ==
            KeyEvent.ACTION_DOWN &&
            event.keyCode ==
            KeyEvent.KEYCODE_MENU
        ) {

            cycleQuality()

            return true
        }

        /*
         * Long press OK/center also changes quality.
         * Useful on SHIELD remotes without a Menu key.
         */
        if (
            event.keyCode ==
            KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode ==
            KeyEvent.KEYCODE_ENTER
        ) {

            if (
                event.action ==
                KeyEvent.ACTION_DOWN &&
                event.repeatCount ==
                0
            ) {

                centerDownTime =
                    System.currentTimeMillis()
            }

            if (
                event.action ==
                KeyEvent.ACTION_UP
            ) {

                val heldFor =
                    System.currentTimeMillis() -
                        centerDownTime

                if (
                    heldFor >=
                    700L
                ) {

                    cycleQuality()

                    return true
                }
            }
        }

        /*
         * Original OK-to-retry behavior.
         */
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

        qualityOverlay.removeCallbacks(
            hideQualityOverlay
        )

        playerView.player =
            null

        player?.release()

        player =
            null

        super.onDestroy()
    }
}