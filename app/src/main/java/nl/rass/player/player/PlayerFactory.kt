package nl.rass.player.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import nl.rass.player.data.feed.FeedUrls
import nl.rass.player.data.source.MediaAuthHeaders
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.VideoCodec
import nl.rass.player.domain.playback.StreamCandidate
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Which source's access header (feed-spec §14) applies to the media requests of the playing candidate.
 * Set before each `setMediaItem`; read by [MediaAuthInterceptor] on OkHttp threads.
 */
class MediaAuthContext {
    @Volatile private var current: Pair<String, String>? = null

    fun set(sourceId: String, streamUrl: String) { current = sourceId to streamUrl }
    fun clear() { current = null }
    internal fun get(): Pair<String, String>? = current
}

/**
 * Network interceptor: adds the source's header to every hop whose origin equals the stream URL's origin
 * (manifest, segments, keys on that origin). A redirect to another origin never gets it, and nothing is ever
 * sent to another source's URLs (feed-spec §14). Network-interceptor headers are not copied onto redirects.
 */
internal class MediaAuthInterceptor(
    private val context: MediaAuthContext,
    private val headers: MediaAuthHeaders,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val (sourceId, streamUrl) = context.get() ?: return chain.proceed(req)
        val base = FeedUrls.origin(streamUrl) ?: return chain.proceed(req)
        if (FeedUrls.origin(req.url) != base) return chain.proceed(req)
        val h = headers.headersFor(sourceId, streamUrl)
        if (h.isEmpty()) return chain.proceed(req)
        val b = req.newBuilder()
        h.forEach { (k, v) -> b.header(k, v) }
        return chain.proceed(b.build())
    }
}

/** One ExoPlayer + its track selector and auth context. */
class PlayerHandle(val player: ExoPlayer, val trackSelector: DefaultTrackSelector, val auth: MediaAuthContext)

/**
 * Builds the app's ExoPlayer: media over the SHARED OkHttpClient (private connection + kill switch, R10),
 * `asset:///` via DefaultDataSource, HLS/DASH/MP4, hardware decoders first with fallback, a LoadControl sized
 * for a 2 GB TV, Media3's text renderer disabled (subtitles are drawn by SubtitleOverlay), and seamless-only
 * frame-rate switching (R11.12).
 */
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val mediaAuth: MediaAuthHeaders,
) {
    @OptIn(UnstableApi::class)
    fun create(): PlayerHandle {
        val auth = MediaAuthContext()
        // Same proxy selector + kill-switch interceptors; no HTTP disk cache for video (would evict subtitles).
        val mediaClient = client.newBuilder()
            .cache(null)
            .addNetworkInterceptor(MediaAuthInterceptor(auth, mediaAuth))
            .build()
        val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(mediaClient))

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setPreferredAudioLanguages("fa", "en")
                .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        return PlayerHandle(player, trackSelector, auth)
    }

    /** The display's max video height `D` (feed-spec §7.2). */
    fun displayMaxHeight(): Int = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        d.supportedModes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
    }.getOrNull()?.takeIf { it > 0 } ?: 1080

    companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 40_000
        const val START_BUFFER_MS = 2_000
        const val REBUFFER_MS = 4_000
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024

        fun mediaItem(c: StreamCandidate): MediaItem = MediaItem.Builder()
            .setUri(c.url)
            .setMimeType(
                when (c.stream.type) {
                    MediaType.HLS -> MimeTypes.APPLICATION_M3U8
                    MediaType.DASH -> MimeTypes.APPLICATION_MPD
                    MediaType.MP4 -> MimeTypes.VIDEO_MP4
                },
            )
            .build()
    }
}

/** Hardware decoder availability per feed codec hint (feed-spec §7.2 key 1). Cached; errs on "decodable". */
object CodecSupport {
    private val cache = ConcurrentHashMap<VideoCodec, Boolean>()

    fun isHardwareDecodable(codec: VideoCodec): Boolean = cache.getOrPut(codec) { query(mime(codec)) }

    private fun mime(c: VideoCodec) = when (c) {
        VideoCodec.H264 -> MimeTypes.VIDEO_H264
        VideoCodec.HEVC -> MimeTypes.VIDEO_H265
        VideoCodec.VP9 -> MimeTypes.VIDEO_VP9
        VideoCodec.AV1 -> MimeTypes.VIDEO_AV1
    }

    private fun query(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
    }.getOrDefault(true)
}
