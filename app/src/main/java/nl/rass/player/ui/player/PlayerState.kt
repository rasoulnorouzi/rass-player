package nl.rass.player.ui.player

import nl.rass.player.domain.CatalogSeason
import nl.rass.player.domain.Quality
import nl.rass.player.domain.SourcedSubtitle
import nl.rass.player.domain.WatchProgress
import nl.rass.player.player.QualityOption
import nl.rass.player.player.SkipTarget
import nl.rass.player.player.SleepTimerOption
import nl.rass.player.player.subtitle.SubtitleStyle
import nl.rass.player.player.subtitle.SubtitleTrack
import nl.rass.player.player.subtitle.online.OnlineSubtitleResult

enum class PlayerPhase { LOADING, PLAYING, NOT_FOUND, EXHAUSTED, PRIVATE_CONNECTION }

/** One row of the Source menu (feed-spec §7.4). */
data class SourceRow(
    val index: Int,
    val label: String,
    val sourceName: String,
    val quality: Quality,
    val bitrateKbps: Int?,
    val failed: Boolean,
    val current: Boolean,
)

data class AudioOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

/** A subtitle the menu offers: pooled from a source (R6.6) or found online (R6.7). */
data class SubtitleChoice(
    val id: String,
    val lang: String,
    val label: String,
    /** Source name or online provider (R6.6: "the menu shows where each subtitle comes from"). */
    val origin: String,
    val pooled: SourcedSubtitle? = null,
    val online: OnlineSubtitleResult? = null,
) {
    /** URL the sync offset is remembered under (feed-spec §8: per item and per subtitle URL). */
    val offsetUrl: String get() = pooled?.url ?: "online:${online?.key}"
}

data class SubtitleUi(
    val enabled: Boolean = false,
    /** "fa" or "en". */
    val lang: String = "fa",
    val choices: List<SubtitleChoice> = emptyList(),
    val selectedId: String? = null,
    val track: SubtitleTrack? = null,
    val offsetMs: Long = 0,
    val style: SubtitleStyle = SubtitleStyle.DEFAULT,
    val loading: Boolean = false,
    val searching: Boolean = false,
)

data class Notice(val id: Int, val text: String)

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.LOADING,
    val titleText: String = "",
    /** R13: NOT_FOUND because the content filter hides the title. */
    val hiddenByFilter: Boolean = false,
    val episodeLabel: String? = null,
    val isSeries: Boolean = false,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    /** Non-null while the «ادامه از …» dialog shows. */
    val resumePromptMs: Long? = null,
    val notice: Notice? = null,
    val offline: Boolean = false,
    val sources: List<SourceRow> = emptyList(),
    val quality: List<QualityOption> = emptyList(),
    val audio: List<AudioOption> = emptyList(),
    val subtitle: SubtitleUi = SubtitleUi(),
    val speed: Float = 1f,
    val sleep: SleepTimerOption = SleepTimerOption.Off,
    val sleepRemainingMs: Long? = null,
    val skip: SkipTarget? = null,
    /** Auto-next countdown (R5.2); null when not showing. */
    val autoNextMs: Long? = null,
    val seasons: List<CatalogSeason> = emptyList(),
    val currentEpisodeKey: String? = null,
    val episodeProgress: Map<String, WatchProgress> = emptyMap(),
    /** Series ended without auto-next: keep the controls up. */
    val ended: Boolean = false,
)
