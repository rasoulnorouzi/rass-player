package nl.rass.player.player.subtitle

/**
 * User-adjustable subtitle look (R6.2, R6.5). Colors are ARGB ints so this stays plain Kotlin and can be
 * stored in DataStore later without Compose types.
 */
data class SubtitleStyle(
    val textColor: Int = SubtitleColor.WHITE.argb,
    val backgroundEnabled: Boolean = false,
    /** Opacity of the black box behind each line, 0..1. Used only when [backgroundEnabled]. */
    val backgroundOpacity: Float = 0.6f,
    /** Multiplier on the base subtitle size. */
    val sizeScale: Float = 1f,
    /** 0 = top of the safe area, 1 = bottom of the safe area. */
    val verticalPosition: Float = BOTTOM,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
) {
    fun withColor(color: SubtitleColor) = copy(textColor = color.argb)
    fun atTop() = copy(verticalPosition = TOP)
    fun atBottom() = copy(verticalPosition = BOTTOM)

    /** Moves the subtitles; a negative [delta] moves them up. */
    fun moveBy(delta: Float) = copy(verticalPosition = (verticalPosition + delta).coerceIn(TOP, BOTTOM))

    fun scaledBy(step: Float) = copy(sizeScale = (sizeScale + step).coerceIn(MIN_SCALE, MAX_SCALE))

    companion object {
        const val TOP = 0f
        const val BOTTOM = 1f
        const val POSITION_STEP = 0.05f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.0f
        const val SCALE_STEP = 0.1f

        val DEFAULT = SubtitleStyle()
        val PRESETS: List<SubtitleStyle> = SubtitleColor.entries.map { DEFAULT.withColor(it) }
    }
}

/** The four color presets offered in the player menu (R6.5). */
enum class SubtitleColor(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()),
    YELLOW(0xFFFFE14D.toInt()),
    CYAN(0xFF4DE8FF.toInt()),
    GREEN(0xFF5CFF7A.toInt()),
}

enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }
