@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.rass.player.ui.player

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.rass.player.R
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.feed.AssetReader
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.settings.SubtitleLanguagePref
import nl.rass.player.data.settings.SubtitleStyleCodec
import nl.rass.player.data.source.MediaAuthHeaders
import nl.rass.player.di.AppScope
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Quality
import nl.rass.player.domain.SourcedSubtitle
import nl.rass.player.domain.SubtitleFileFormat
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.WatchRules
import nl.rass.player.domain.playback.PlayReason
import nl.rass.player.domain.playback.PlaybackEvent
import nl.rass.player.domain.playback.QualityPreference
import nl.rass.player.domain.playback.SelectorDecision
import nl.rass.player.domain.playback.StreamCandidate
import nl.rass.player.domain.playback.StreamCandidates
import nl.rass.player.domain.playback.StreamSelector
import nl.rass.player.net.PrivateConnectionUnavailableException
import nl.rass.player.net.ProxyManager
import nl.rass.player.player.AutoNextRules
import nl.rass.player.player.CodecSupport
import nl.rass.player.player.ConnectivityMonitor
import nl.rass.player.player.PlayerFactory
import nl.rass.player.player.PlayerHandle
import nl.rass.player.player.QualityMenu
import nl.rass.player.player.QualityOption
import nl.rass.player.player.ResumeDecision
import nl.rass.player.player.ResumeRules
import nl.rass.player.player.SeekAccelerator
import nl.rass.player.player.SkipRules
import nl.rass.player.player.SleepTimerOption
import nl.rass.player.player.VariantInfo
import nl.rass.player.player.subtitle.SubtitleFormat
import nl.rass.player.player.subtitle.SubtitleLoader
import nl.rass.player.player.subtitle.SubtitleOffset
import nl.rass.player.player.subtitle.SubtitleOffsetKey
import nl.rass.player.player.subtitle.SubtitleOffsetStore
import nl.rass.player.player.subtitle.SubtitleStyle
import nl.rass.player.player.subtitle.SubtitleTrack
import nl.rass.player.player.subtitle.online.AutoPick
import nl.rass.player.player.subtitle.online.AutoPickPrefs
import nl.rass.player.player.subtitle.online.OnlineSubtitleRepository
import nl.rass.player.player.subtitle.online.OnlineSubtitleResult
import nl.rass.player.player.subtitle.online.PooledSubtitle
import nl.rass.player.player.subtitle.online.SubtitleAutoPicker
import nl.rass.player.player.subtitle.online.SubtitleQuery
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.navigation.Route
import okhttp3.OkHttpClient

/**
 * One player session (R4, R5, R6, R11): ExoPlayer, failover (StreamSelector, feed-spec §7.3), resume and
 * progress, episodes, subtitles (own overlay), quality/audio/source/speed/sleep.
 *
 * Lifecycle is explicit so it is correct whatever ViewModel scope the shell uses: [start] opens a session for a
 * route, [exit] saves and releases the player. All calls happen on the main thread.
 */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var c: Throwable? = this
    var depth = 0
    while (c != null && depth++ < 10) {
        if (c is T) return true
        c = c.cause
    }
    return false
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepository: CatalogRepository,
    private val progressRepository: WatchProgressRepository,
    private val library: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val offsetStore: SubtitleOffsetStore,
    private val onlineSubtitles: OnlineSubtitleRepository,
    private val mediaAuth: MediaAuthHeaders,
    private val proxyManager: ProxyManager,
    private val connectivity: ConnectivityMonitor,
    private val playerFactory: PlayerFactory,
    client: OkHttpClient,
    assets: AssetReader,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val subtitleLoader = SubtitleLoader(client, assets = assets)

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    /** Playback position at ~10 Hz for the subtitle overlay and the progress bar. */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** Pending seek target while Left/Right is pressed (time preview, R5.5). */
    private val _seekPreview = MutableStateFlow<Long?>(null)
    val seekPreview: StateFlow<Long?> = _seekPreview.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val exitChannel = Channel<Unit>(Channel.CONFLATED)
    /** The screen should close (movie ended, or nothing to play). */
    val exitRequests: Flow<Unit> = exitChannel.receiveAsFlow()

    // ---- session state (main thread) -------------------------------------------------------------------
    private var session: CoroutineScope? = null
    private var handle: PlayerHandle? = null
    private var route: Route.Player? = null
    private var title: CatalogTitle? = null
    private var episode: CatalogEpisode? = null
    private var itemKey = ""
    private var selector: StreamSelector? = null
    private var settings = AppSettings()
    private var qualityPref: QualityPreference = QualityPreference.Auto
    private var displayMax = 1080
    private var anchorMs = 0L
    private var firstFrame = false
    private var awaitingResume = false
    private var wantPlaying = true
    private var autoNextDismissed = false
    private var variantOverride: VariantInfo? = null
    private var audioLang: String? = null
    private var onlineResults: List<OnlineSubtitleResult>? = null
    private var subtitleJob: Job? = null
    private var pendingOffset: Pair<SubtitleOffsetKey, Long>? = null
    private var offsetSaveJob: Job? = null
    private var noticeJob: Job? = null
    private var seekCommitJob: Job? = null
    private var sleepEndsAt: Long? = null
    private var lastSaveAt = 0L
    private var noticeSeq = 0
    private var proxyActive = false
    private val seekAccelerator = SeekAccelerator()

    private val p: Player? get() = handle?.player
    private fun now() = SystemClock.elapsedRealtime()

    // ---- lifecycle ---------------------------------------------------------------------------------------

    fun start(route: Route.Player) {
        if (session != null && this.route == route) return
        if (session != null) exit()
        this.route = route
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        session = scope
        _ui.value = PlayerUiState()
        scope.launch {
            settings = settingsRepository.current()
            qualityPref = QualityPreference.parse(settings.defaultQuality)
            displayMax = playerFactory.displayMaxHeight()
            val subOn = settings.subtitlesAutoOn && settings.subtitleLanguage != SubtitleLanguagePref.OFF
            val lang = if (settings.subtitleLanguage == SubtitleLanguagePref.EN) "en" else "fa"
            _ui.update {
                it.copy(subtitle = SubtitleUi(enabled = subOn, lang = lang, style = SubtitleStyleCodec.decode(settings.subtitleStyleJson)))
            }

            val t = withTimeoutOrNull(TITLE_WAIT_MS) {
                catalogRepository.state.mapNotNull { it.catalog.title(route.catalogKey) }.first()
            }
            if (t == null) {
                _ui.update { it.copy(phase = PlayerPhase.NOT_FOUND) }
                return@launch
            }
            title = t
            createPlayer()
            launchLoops(scope, t)
            val ep = if (t.type == TitleType.SERIES) resolveEpisode(t, route) else null
            if (t.type == TitleType.SERIES && ep == null) {
                _ui.update { it.copy(phase = PlayerPhase.NOT_FOUND) }
                return@launch
            }
            openItem(ep, route.startFromBeginning)
        }
    }

    /** Saves progress and releases everything. Safe to call more than once. */
    fun exit() {
        if (session == null) return
        saveProgress()
        flushOffset()
        session?.cancel()
        session = null
        handle?.let {
            it.player.removeListener(listener)
            it.player.release()
            it.auth.clear()
        }
        handle = null
        _player.value = null
        proxyActive = false
        proxyManager.setPlaybackActive(false)
        selector = null
        title = null
        episode = null
        route = null
    }

    /** Activity stopped (TV went to standby / home): pause and save (R4.1). */
    fun onStop() {
        val pl = p ?: return
        wantPlaying = false
        pl.playWhenReady = false
        saveProgress()
    }

    override fun onCleared() {
        exit()
    }

    private fun createPlayer() {
        if (handle != null) return
        val h = playerFactory.create()
        h.player.addListener(listener)
        handle = h
        _player.value = h.player
    }

    private fun launchLoops(scope: CoroutineScope, t: CatalogTitle) {
        scope.launch {
            while (isActive) {
                p?.let { _position.value = it.currentPosition }
                delay(POSITION_INTERVAL_MS)
            }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
        scope.launch {
            connectivity.isOnline.collect { online ->
                val s = selector ?: return@collect
                if (online == s.isOnline) return@collect
                captureAnchor()
                handle(s.onEvent(if (online) PlaybackEvent.NetworkRestored else PlaybackEvent.NetworkLost, now()))
            }
        }
        if (t.type == TitleType.SERIES) {
            scope.launch {
                progressRepository.observeTitle(t.key).collect { map -> _ui.update { it.copy(episodeProgress = map) } }
            }
        }
    }

    private suspend fun resolveEpisode(t: CatalogTitle, route: Route.Player): CatalogEpisode? {
        val s = route.seasonNumber
        val e = route.episodeNumber
        if (s != null && e != null) {
            t.seasons.firstOrNull { it.number == s }?.episodes?.firstOrNull { it.number == e }?.let { return it }
        }
        return progressRepository.nextEpisode(t)?.first ?: WatchRules.playOrder(t).firstOrNull()
    }

    // ---- items -------------------------------------------------------------------------------------------

    private suspend fun openItem(ep: CatalogEpisode?, startFromBeginning: Boolean) {
        val t = title ?: return
        episode = ep
        itemKey = ep?.key.orEmpty()
        autoNextDismissed = false
        variantOverride = null
        firstFrame = false
        library.recordPlay(t.key, t.type, itemKey)

        val decision = ResumeRules.decide(progressRepository.get(t.key, itemKey), settings.resumeBehavior, startFromBeginning)
        anchorMs = when (decision) {
            ResumeDecision.StartOver -> 0
            is ResumeDecision.Resume -> decision.positionMs
            is ResumeDecision.Ask -> decision.positionMs
        }
        awaitingResume = decision is ResumeDecision.Ask
        wantPlaying = true
        lastSaveAt = now()

        val order = WatchRules.playOrder(t)
        val idx = ep?.let { e -> order.indexOfFirst { it.key == e.key } } ?: -1
        _ui.update {
            it.copy(
                phase = PlayerPhase.PLAYING,
                titleText = t.meta.displayTitle,
                episodeLabel = ep?.let { e -> episodeLabel(e) },
                isSeries = t.type == TitleType.SERIES,
                hasPrev = idx > 0,
                hasNext = idx >= 0 && idx < order.size - 1,
                durationMs = 0,
                resumePromptMs = (decision as? ResumeDecision.Ask)?.positionMs,
                skip = null,
                autoNextMs = null,
                ended = false,
                seasons = t.seasons,
                currentEpisodeKey = ep?.key,
                audio = emptyList(),
            )
        }
        _position.value = anchorMs

        val sel = StreamSelector(buildCandidates(), online = connectivity.isOnline.value)
        selector = sel
        handle(sel.start(now()))
        setupSubtitles()
    }

    private fun buildCandidates(pref: QualityPreference = qualityPref): List<StreamCandidate> {
        val streams = episode?.streams ?: title?.streams.orEmpty()
        return StreamCandidates.order(streams, pref, displayMax, CodecSupport::isHardwareDecodable)
    }

    private fun episodeLabel(e: CatalogEpisode): String {
        val head = context.getString(R.string.player_episode_label, e.seasonNumber.fa(), e.number.fa())
        val name = e.titleFa ?: e.titleEn
        return if (name != null) "$head: $name" else head
    }

    private fun nextEpisode(): CatalogEpisode? = neighbour(+1)
    private fun prevEpisode(): CatalogEpisode? = neighbour(-1)

    private fun neighbour(delta: Int): CatalogEpisode? {
        val t = title ?: return null
        val e = episode ?: return null
        val order = WatchRules.playOrder(t)
        val i = order.indexOfFirst { it.key == e.key }
        return if (i < 0) null else order.getOrNull(i + delta)
    }

    fun playNext() { nextEpisode()?.let { playEpisode(it) } }
    fun playPrev() { prevEpisode()?.let { playEpisode(it) } }

    /** Episodes panel / prev / next / auto-next: switch without leaving the player (R5.1, R5.8). */
    fun playEpisode(ep: CatalogEpisode) {
        val scope = session ?: return
        saveProgress()
        p?.playWhenReady = false
        scope.launch { openItem(ep, startFromBeginning = false) }
    }

    fun cancelAutoNext() {
        autoNextDismissed = true
        _ui.update { it.copy(autoNextMs = null) }
    }

    // ---- candidates / failover ---------------------------------------------------------------------------

    private fun captureAnchor() {
        val pl = p ?: return
        if (firstFrame && pl.playbackState != Player.STATE_ENDED) anchorMs = pl.currentPosition
    }

    private fun handle(d: SelectorDecision) {
        when (d) {
            SelectorDecision.None -> return
            is SelectorDecision.Play -> {
                val pl = p
                if (d.reason == PlayReason.NETWORK_RESTORED && pl != null && pl.playbackState != Player.STATE_IDLE) {
                    // Still playing from the buffer: no need to reopen; keep the selector's start timer satisfied.
                    if (firstFrame) selector?.onEvent(PlaybackEvent.FirstFrame, now())
                } else {
                    openCandidate(d.candidate)
                }
                d.notice?.let {
                    showNotice(context.getString(R.string.player_notice_failover, it.position.fa(), it.total.fa(), it.label))
                }
                _ui.update { it.copy(phase = PlayerPhase.PLAYING, offline = false) }
            }
            SelectorDecision.WaitingForNetwork -> _ui.update { it.copy(offline = true) }
            SelectorDecision.Exhausted -> {
                p?.stop()
                _ui.update { it.copy(phase = PlayerPhase.EXHAUSTED, buffering = false) }
            }
        }
        refreshSources()
        refreshQuality()
    }

    private fun openCandidate(c: StreamCandidate) {
        val h = handle ?: return
        val pl = h.player
        firstFrame = false
        variantOverride = null
        h.auth.set(c.sourceId, c.url)
        val pref = qualityPref
        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .apply {
                if (pref is QualityPreference.Height && c.stream.quality == Quality.Auto) setMaxVideoSize(Int.MAX_VALUE, pref.px)
                else clearVideoSizeConstraints()
                audioLang?.let { setPreferredAudioLanguage(it) }
            }
            .build()
        pl.setMediaItem(PlayerFactory.mediaItem(c), anchorMs)
        pl.prepare()
        pl.playWhenReady = wantPlaying && !awaitingResume
    }

    private fun selectorEvent(e: PlaybackEvent) {
        val s = selector ?: return
        captureAnchor()
        handle(s.onEvent(e, now()))
    }

    /** Source menu (R5.7). */
    fun selectSource(index: Int) {
        val s = selector ?: return
        if (index !in s.candidates.indices) return
        captureAnchor()
        handle(s.select(index, now()))
    }

    /** Error screen "Try again" (§7.3). */
    fun retry() {
        val s = selector ?: return
        captureAnchor()
        wantPlaying = true
        val cur = s.currentIndex
        handle(if (_ui.value.phase == PlayerPhase.PRIVATE_CONNECTION && cur >= 0) s.select(cur, now()) else s.retryAll(now()))
    }

    private fun refreshSources() {
        val s = selector ?: return
        _ui.update {
            it.copy(
                sources = s.candidates.mapIndexed { i, c ->
                    SourceRow(i, c.displayLabel, c.sourceName, c.stream.quality, c.stream.bitrateKbps, s.isFailed(i), i == s.currentIndex)
                },
            )
        }
    }

    // ---- player events -----------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (firstFrame) return
            firstFrame = true
            selector?.onEvent(PlaybackEvent.FirstFrame, now())
        }

        override fun onPlaybackStateChanged(state: Int) {
            updateProxyActivity()
            when (state) {
                Player.STATE_BUFFERING -> {
                    _ui.update { it.copy(buffering = true) }
                    selectorEvent(PlaybackEvent.BufferingStarted)
                }
                Player.STATE_READY -> {
                    _ui.update { it.copy(buffering = false, durationMs = durationOrZero()) }
                    // Audio-only media never renders a frame; READY counts as started.
                    val hasVideo = p?.currentTracks?.containsType(C.TRACK_TYPE_VIDEO) == true
                    if (!firstFrame && !hasVideo && p?.currentTracks?.isEmpty == false) onRenderedFirstFrame()
                    selectorEvent(PlaybackEvent.BufferingEnded)
                }
                Player.STATE_ENDED -> onEnded()
                else -> _ui.update { it.copy(buffering = false) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateProxyActivity()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateProxyActivity()
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying && p?.playWhenReady == false) saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.hasCause<PrivateConnectionUnavailableException>()) {
                captureAnchor()
                _ui.update { it.copy(phase = PlayerPhase.PRIVATE_CONNECTION, buffering = false) }
                return
            }
            selectorEvent(PlaybackEvent.Error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshQuality()
            refreshAudio(tracks)
        }
    }

    /**
     * The private connection pauses its health checks while video plays (docs/private-connection.md). "Plays"
     * includes buffering with play intent, so probes don't compete with a stalled stream for bandwidth.
     */
    private fun updateProxyActivity() {
        val pl = p
        val active = pl != null && pl.playWhenReady &&
            (pl.playbackState == Player.STATE_READY || pl.playbackState == Player.STATE_BUFFERING)
        if (active != proxyActive) {
            proxyActive = active
            proxyManager.setPlaybackActive(active)
        }
    }

    private fun durationOrZero(): Long = p?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun onEnded() {
        saveProgress()
        if (_ui.value.sleep == SleepTimerOption.EndOfItem) {
            fireSleep()
            return
        }
        val next = nextEpisode()
        when {
            next != null && settings.autoplayNext && !autoNextDismissed -> playEpisode(next)
            title?.type == TitleType.SERIES && next != null -> _ui.update { it.copy(ended = true, autoNextMs = null) }
            else -> exitChannel.trySend(Unit)
        }
    }

    private fun tick() {
        val pl = p ?: return
        val now = now()
        if (firstFrame && pl.playbackState == Player.STATE_READY) anchorMs = pl.currentPosition
        selector?.let { s -> if (s.currentIndex >= 0) handle(s.onEvent(PlaybackEvent.Tick, now)) }

        val dur = durationOrZero()
        val pos = pl.currentPosition
        val markers = selector?.current?.markers
        val sleep = _ui.value.sleep
        val autoNext = if (awaitingResume || !firstFrame) null else AutoNextRules.remainingMs(
            pos, dur, markers?.creditsStart,
            enabled = settings.autoplayNext && sleep != SleepTimerOption.EndOfItem,
            hasNext = nextEpisode() != null,
            dismissed = autoNextDismissed,
        )
        if (pl.isPlaying && now - lastSaveAt >= SAVE_INTERVAL_MS) saveProgress()
        val ends = sleepEndsAt
        if (ends != null && now >= ends) fireSleep()

        _ui.update {
            it.copy(
                durationMs = dur,
                skip = if (firstFrame) SkipRules.at(pos, markers) else null,
                autoNextMs = autoNext,
                sleepRemainingMs = sleepEndsAt?.let { e -> (e - now).coerceAtLeast(0) },
            )
        }
        if (autoNext == 0L && pl.isPlaying) nextEpisode()?.let { playEpisode(it) }
    }

    // ---- progress (R4.1, R4.3) ---------------------------------------------------------------------------

    private fun saveProgress() {
        val pl = p ?: return
        val t = title ?: return
        if (awaitingResume) return
        val dur = durationOrZero()
        if (dur <= 0) return
        val pos = if (pl.playbackState == Player.STATE_ENDED) dur else pl.currentPosition
        if (!firstFrame && pos <= 0) return
        lastSaveAt = now()
        val key = t.key
        val ek = itemKey
        val credits = selector?.current?.markers?.creditsStart
        appScope.launch { runCatching { progressRepository.save(key, ek, pos, dur, credits) } }
    }

    // ---- transport ---------------------------------------------------------------------------------------

    fun onResumeChoice(resume: Boolean) {
        val pl = p ?: return
        awaitingResume = false
        _ui.update { it.copy(resumePromptMs = null) }
        if (!resume) {
            anchorMs = 0
            pl.seekTo(0)
            selector?.onEvent(PlaybackEvent.UserSeek, now())
        }
        wantPlaying = true
        pl.playWhenReady = true
    }

    fun togglePlayPause() {
        val pl = p ?: return
        if (awaitingResume) return
        if (pl.playbackState == Player.STATE_ENDED) {
            pl.seekTo(0)
            wantPlaying = true
        } else {
            wantPlaying = !pl.playWhenReady
        }
        if (pl.playbackState == Player.STATE_IDLE && wantPlaying && selector?.current != null) {
            captureAnchor()
            openCandidate(selector!!.current!!)
            return
        }
        pl.playWhenReady = wantPlaying
        _ui.update { it.copy(ended = false) }
    }

    fun setPlaying(play: Boolean) {
        val pl = p ?: return
        if (awaitingResume) return
        wantPlaying = play
        pl.playWhenReady = play
    }

    /** Left/Right (R5.5). [repeat] = auto-repeated key-down. */
    fun seekStep(forward: Boolean, repeat: Boolean) {
        val pl = p ?: return
        if (awaitingResume) return
        val step = seekAccelerator.onKeyDown(now(), repeat) ?: return
        val from = _seekPreview.value ?: pl.currentPosition
        _seekPreview.value = SeekAccelerator.target(from, if (forward) step else -step, durationOrZero())
        seekCommitJob?.cancel()
        seekCommitJob = session?.launch {
            delay(SEEK_IDLE_COMMIT_MS)
            commitSeek()
        }
    }

    /** Key released: jump to the previewed time. */
    fun commitSeek() {
        seekCommitJob?.cancel()
        val target = _seekPreview.value ?: return
        _seekPreview.value = null
        seekTo(target)
    }

    fun seekTo(ms: Long) {
        val pl = p ?: return
        selector?.onEvent(PlaybackEvent.UserSeek, now())
        if (!firstFrame) anchorMs = ms
        pl.seekTo(ms)
        _position.value = ms
        if (ms < durationOrZero() - AutoNextRules.DEFAULT_LEAD_MS) autoNextDismissed = false
        _ui.update { it.copy(ended = false) }
    }

    fun skip() {
        val target = _ui.value.skip ?: return
        seekTo(target.toMs)
    }

    fun setSpeed(speed: Float) {
        p?.setPlaybackSpeed(speed)
        _ui.update { it.copy(speed = speed) }
    }

    fun setSleep(option: SleepTimerOption) {
        sleepEndsAt = (option as? SleepTimerOption.Minutes)?.let { now() + it.minutes * 60_000L }
        _ui.update { it.copy(sleep = option, sleepRemainingMs = sleepEndsAt?.let { e -> e - now() }) }
    }

    private fun fireSleep() {
        sleepEndsAt = null
        setPlaying(false)
        saveProgress()
        _ui.update { it.copy(sleep = SleepTimerOption.Off, sleepRemainingMs = null) }
        showNotice(context.getString(R.string.player_sleep_done))
    }

    // ---- quality / audio ---------------------------------------------------------------------------------

    private fun refreshQuality() {
        val s = selector ?: return
        val variants = p?.currentTracks?.let(::videoVariants).orEmpty()
        _ui.update { it.copy(quality = QualityMenu.build(s.current, s.candidates, variants, variantOverride)) }
    }

    private fun videoVariants(tracks: Tracks): List<VariantInfo> =
        tracks.groups.flatMapIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_VIDEO) emptyList()
            else (0 until g.length).filter { g.isTrackSupported(it) }.map { ti ->
                val f = g.getTrackFormat(ti)
                VariantInfo(gi, ti, f.height, f.bitrate)
            }
        }

    /** Quality menu (feed-spec §7.4, R5.3). */
    fun selectQuality(option: QualityOption) {
        val pl = p ?: return
        val s = selector ?: return
        val current = s.current
        when (option) {
            is QualityOption.Auto -> {
                qualityPref = QualityPreference.Auto
                if (current?.stream?.quality == Quality.Auto) {
                    variantOverride = null
                    pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearVideoSizeConstraints().build()
                    refreshQuality()
                } else {
                    changeQuality()
                }
            }
            is QualityOption.Variant -> {
                val g = pl.currentTracks.groups.getOrNull(option.info.groupIndex) ?: return
                variantOverride = option.info
                selector?.onEvent(PlaybackEvent.UserSeek, now()) // manual change: grace for the rebuffer
                pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.info.trackIndex))
                    .build()
                refreshQuality()
            }
            is QualityOption.Fixed -> {
                qualityPref = QualityMenu.preferenceAfter(option, qualityPref)
                changeQuality()
            }
        }
    }

    private fun changeQuality() {
        val s = selector ?: return
        captureAnchor()
        handle(s.changeQuality(buildCandidates(), now()))
    }

    private fun refreshAudio(tracks: Tracks) {
        val opts = ArrayList<AudioOption>()
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val name = f.label ?: languageNameFa(f.language)
                val ch = if (f.channelCount > 2) " · ${f.channelCount.fa()} کانال" else ""
                opts += AudioOption(gi, ti, name + ch, g.isTrackSelected(ti))
            }
        }
        // R11.6: only when the stream really has more than one audio track.
        _ui.update { it.copy(audio = if (opts.size > 1) opts else emptyList()) }
    }

    fun selectAudio(option: AudioOption) {
        val pl = p ?: return
        val g = pl.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        audioLang = g.getTrackFormat(option.trackIndex).language
        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.trackIndex))
            .apply { audioLang?.let { setPreferredAudioLanguage(it) } }
            .build()
    }

    // ---- subtitles (R5.4, R6) ----------------------------------------------------------------------------

    private fun pooledSubtitles(): List<SourcedSubtitle> =
        (episode?.subtitles ?: title?.subtitles.orEmpty()).filter { it.lang == "fa" || it.lang == "en" }

    private fun pooledChoice(s: SourcedSubtitle) = SubtitleChoice(
        id = "src:${s.url}",
        lang = s.lang,
        label = s.subtitle.label ?: langFa(s.lang),
        origin = s.sourceName,
        pooled = s,
    )

    private fun onlineChoice(r: OnlineSubtitleResult) = SubtitleChoice(
        id = "online:${r.key}",
        lang = r.lang,
        label = r.releaseName.ifBlank { langFa(r.lang) },
        origin = r.provider,
        online = r,
    )

    private fun setupSubtitles() {
        subtitleJob?.cancel()
        flushOffset()
        onlineResults = null
        val choices = pooledSubtitles().map(::pooledChoice)
        _ui.update { it.copy(subtitle = it.subtitle.copy(choices = choices, selectedId = null, track = null, offsetMs = 0, loading = false, searching = false)) }
        if (_ui.value.subtitle.enabled) autoPick(_ui.value.subtitle.lang)
    }

    /** R6.8: the playing source's own subtitle, else best pooled, else best online; with a notice. */
    private fun autoPick(lang: String) {
        val scope = session ?: return
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            val pooled = pooledSubtitles()
            val sourceId = selector?.current?.sourceId
            val own = pooled.filter { it.sourceId == sourceId }
            val lite = pooled.map { PooledSubtitle(it.lang, it.sourceName, it.url) }
            val prefs = AutoPickPrefs(subtitlesOn = true, preferredLang = lang)
            var pick = SubtitleAutoPicker.pick(prefs, own.mapTo(HashSet()) { it.lang }, lite, onlineResults)
            if (pick is AutoPick.NeedsOnlineSearch) {
                val results = searchOnlineQuiet()
                pick = SubtitleAutoPicker.pick(prefs, own.mapTo(HashSet()) { it.lang }, lite, results)
            }
            when (pick) {
                is AutoPick.Keep -> {
                    val s = own.firstOrNull { it.lang == lang } ?: own.firstOrNull() ?: return@launch
                    loadChoice(pooledChoice(s))
                }
                is AutoPick.Pooled -> {
                    val s = pooled.firstOrNull { it.url == pick.subtitle.url } ?: return@launch
                    pick.noticeFa?.let(::showNotice)
                    loadChoice(pooledChoice(s))
                }
                is AutoPick.Online -> {
                    pick.noticeFa?.let(::showNotice)
                    loadChoice(onlineChoice(pick.result))
                }
                AutoPick.NothingFound -> pick.noticeFa?.let(::showNotice)
                AutoPick.NeedsOnlineSearch -> Unit
            }
        }
    }

    private fun subtitleQuery(): SubtitleQuery? {
        val t = title ?: return null
        val m = t.meta
        return SubtitleQuery(
            imdbId = m.ids.imdb,
            tmdbId = m.ids.tmdb?.toLong(),
            title = m.titleEn ?: m.originalTitle ?: m.displayTitle,
            year = m.year,
            season = episode?.seasonNumber,
            episode = episode?.number,
            isSeries = t.type == TitleType.SERIES,
            releaseHint = selector?.current?.url?.substringAfterLast('/')?.substringBefore('?'),
        )
    }

    /** Search without UI errors (auto-pick). Returns ranked fa/en results (empty on failure). */
    private suspend fun searchOnlineQuiet(): List<OnlineSubtitleResult> {
        val q = subtitleQuery() ?: return emptyList()
        val results = try {
            onlineSubtitles.search(q).results.filter { it.lang == "fa" || it.lang == "en" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        mergeOnline(results)
        return results
    }

    private fun mergeOnline(results: List<OnlineSubtitleResult>) {
        onlineResults = results
        val choices = pooledSubtitles().map(::pooledChoice) + results.map(::onlineChoice)
        _ui.update { it.copy(subtitle = it.subtitle.copy(choices = choices)) }
    }

    /** "Search online" in the subtitle menu (R6.7, on demand). */
    fun searchOnline() {
        val scope = session ?: return
        val q = subtitleQuery() ?: return
        _ui.update { it.copy(subtitle = it.subtitle.copy(searching = true)) }
        scope.launch {
            val outcome = try {
                onlineSubtitles.search(q, forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val results = outcome?.results.orEmpty().filter { it.lang == "fa" || it.lang == "en" }
            mergeOnline(results)
            _ui.update { it.copy(subtitle = it.subtitle.copy(searching = false)) }
            when {
                results.isNotEmpty() -> showNotice(context.getString(R.string.player_online_found, results.size.fa()))
                outcome?.errors?.isNotEmpty() == true -> showNotice(outcome.errors.first().error.messageFa)
                else -> showNotice(context.getString(R.string.player_online_none))
            }
        }
    }

    fun setSubtitlesEnabled(on: Boolean) {
        if (!on) {
            subtitleJob?.cancel()
            flushOffset()
            _ui.update { it.copy(subtitle = it.subtitle.copy(enabled = false, selectedId = null, track = null, offsetMs = 0, loading = false)) }
        } else {
            _ui.update { it.copy(subtitle = it.subtitle.copy(enabled = true)) }
            autoPick(_ui.value.subtitle.lang)
        }
    }

    /** Language choice (fa/en): picks the best subtitle in that language. */
    fun setSubtitleLanguage(lang: String) {
        _ui.update { it.copy(subtitle = it.subtitle.copy(enabled = true, lang = lang)) }
        autoPick(lang)
    }

    fun selectSubtitle(id: String) {
        val choice = _ui.value.subtitle.choices.firstOrNull { it.id == id } ?: return
        val scope = session ?: return
        subtitleJob?.cancel()
        subtitleJob = scope.launch { loadChoice(choice) }
    }

    private suspend fun loadChoice(choice: SubtitleChoice) {
        flushOffset()
        _ui.update { it.copy(subtitle = it.subtitle.copy(enabled = true, lang = choice.lang, selectedId = choice.id, loading = true)) }
        val track: SubtitleTrack? = try {
            val pooled = choice.pooled
            val online = choice.online
            when {
                pooled != null -> subtitleLoader.load(
                    pooled.url,
                    when (pooled.subtitle.format) {
                        SubtitleFileFormat.SRT -> SubtitleFormat.SRT
                        SubtitleFileFormat.VTT -> SubtitleFormat.VTT
                    },
                    pooled.subtitle.encoding,
                    mediaAuth.headersFor(pooled.sourceId, pooled.url),
                )
                online != null -> onlineSubtitles.fetch(online).let { d -> withContext(Dispatchers.Default) { d.toTrack() } }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (track == null || track.isEmpty) {
            showNotice(context.getString(R.string.player_subtitle_failed))
            _ui.update { it.copy(subtitle = it.subtitle.copy(selectedId = null, track = null, loading = false)) }
            return
        }
        val offset = runCatching { offsetStore.get(offsetKey(choice)) }.getOrDefault(0L)
        _ui.update { it.copy(subtitle = it.subtitle.copy(track = track, offsetMs = offset, loading = false)) }
    }

    private fun offsetKey(choice: SubtitleChoice): SubtitleOffsetKey =
        SubtitleOffsetKey.of(title?.key.orEmpty(), itemKey.ifEmpty { null }, choice.offsetUrl)

    private fun selectedChoice(): SubtitleChoice? {
        val s = _ui.value.subtitle
        return s.choices.firstOrNull { it.id == s.selectedId }
    }

    /** R6.3: ±100 ms (hold: ±1 s), range ±60 s, live; remembered per item + subtitle. */
    fun stepOffset(deltaMs: Long) = setOffset(SubtitleOffset.step(_ui.value.subtitle.offsetMs, deltaMs))

    fun resetOffset() = setOffset(0)

    private fun setOffset(v: Long) {
        val choice = selectedChoice() ?: return
        _ui.update { it.copy(subtitle = it.subtitle.copy(offsetMs = v)) }
        pendingOffset = offsetKey(choice) to v
        offsetSaveJob?.cancel()
        offsetSaveJob = session?.launch {
            delay(OFFSET_SAVE_DEBOUNCE_MS)
            flushOffset()
        }
    }

    private fun flushOffset() {
        val (k, v) = pendingOffset ?: return
        pendingOffset = null
        appScope.launch { runCatching { offsetStore.set(k, v) } }
    }

    /** R6.5: color, background, size, position, applied live. */
    fun updateStyle(transform: (SubtitleStyle) -> SubtitleStyle) {
        _ui.update { it.copy(subtitle = it.subtitle.copy(style = transform(it.subtitle.style))) }
    }

    /** "Save as default" (R6.5): copies the live style + language/on to Settings. */
    fun saveSubtitleDefaults() {
        val s = _ui.value.subtitle
        appScope.launch {
            runCatching {
                settingsRepository.update {
                    it.copy(
                        subtitleStyleJson = SubtitleStyleCodec.encode(s.style),
                        subtitlesAutoOn = s.enabled,
                        subtitleLanguage = if (s.lang == "en") SubtitleLanguagePref.EN else SubtitleLanguagePref.FA,
                    )
                }
            }
        }
        showNotice(context.getString(R.string.player_defaults_saved))
    }

    // ---- notices -----------------------------------------------------------------------------------------

    private fun showNotice(text: String) {
        val id = ++noticeSeq
        _ui.update { it.copy(notice = Notice(id, text)) }
        noticeJob?.cancel()
        noticeJob = (session ?: viewModelScope).launch {
            delay(NOTICE_MS)
            _ui.update { if (it.notice?.id == id) it.copy(notice = null) else it }
        }
    }

    private fun langFa(lang: String) =
        context.getString(if (lang == "en") R.string.player_sub_lang_en else R.string.player_sub_lang_fa)

    private fun languageNameFa(code: String?): String = when (code?.lowercase()?.substringBefore('-')) {
        null, "", "und" -> "?"
        "fa", "fas", "per" -> langFa("fa")
        "en", "eng" -> langFa("en")
        else -> runCatching { Locale(code).getDisplayLanguage(Locale("fa")) }.getOrNull()?.takeIf { it.isNotBlank() } ?: code
    }

    private companion object {
        const val TITLE_WAIT_MS = 20_000L
        const val POSITION_INTERVAL_MS = 100L
        const val TICK_MS = 1_000L
        const val SAVE_INTERVAL_MS = 10_000L
        const val NOTICE_MS = 4_000L
        const val SEEK_IDLE_COMMIT_MS = 1_200L
        const val OFFSET_SAVE_DEBOUNCE_MS = 400L
    }
}
