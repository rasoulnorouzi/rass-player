package nl.rass.player.ui.detail

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import nl.rass.player.R
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.di.MediaHttpClient
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.Trailer
import nl.rass.player.net.ProxyManager
import nl.rass.player.ui.components.ErrorState
import okhttp3.OkHttpClient

@HiltViewModel
class TrailerViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    @MediaHttpClient val client: OkHttpClient,
    private val proxyManager: ProxyManager,
) : ViewModel() {
    fun trailerFor(catalogKey: String): Trailer? =
        catalog.state.value.catalog.title(catalogKey)?.meta?.trailer?.takeIf(DetailViewModel::isPlayableTrailer)

    /** Pause the private connection's health checks while video plays (R10). */
    fun setPlaying(active: Boolean) = proxyManager.setPlaybackActive(active)
}

/**
 * Full-screen trailer (R11.7): plain Media3 playback with the stock controller. The shared OkHttpClient
 * (private connection) is used via the media client, which adds the source's access header where allowed.
 * Back returns to the detail page; the end of the trailer does too.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(catalogKey: String, onBack: () -> Unit) {
    val vm: TrailerViewModel = hiltViewModel()
    val trailer = remember(catalogKey) { vm.trailerFor(catalogKey) }
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (trailer != null) {
            val player = remember {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(vm.client)))
                    .build()
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) { error = true }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onBack() }
                }
                player.addListener(listener)
                player.setMediaItem(
                    MediaItem.Builder().setUri(trailer.url)
                        .setMimeType(if (trailer.type == MediaType.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
                        .build(),
                )
                player.prepare()
                player.playWhenReady = true
                vm.setPlaying(true)
                onDispose {
                    vm.setPlaying(false)
                    player.removeListener(listener)
                    player.release()
                }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        keepScreenOn = true
                        isFocusable = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        post { requestFocus() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (error) {
                ErrorState(stringResource(R.string.detail_trailer_error), onRetry = {
                    error = false
                    player.prepare()
                    player.playWhenReady = true
                })
            }
        } else {
            ErrorState(stringResource(R.string.detail_trailer_error), onRetry = onBack)
        }
    }
}
