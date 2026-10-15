package nl.rass.player.data.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.rass.player.player.subtitle.SubtitleEdge
import nl.rass.player.player.subtitle.SubtitleStyle

/** Persists the default [SubtitleStyle] in `AppSettings.subtitleStyleJson` (R6.2). Unknown/bad input gives defaults. */
object SubtitleStyleCodec {
    fun encode(s: SubtitleStyle): String = buildJsonObject {
        put("textColor", s.textColor)
        put("bg", s.backgroundEnabled)
        put("bgOpacity", s.backgroundOpacity)
        put("size", s.sizeScale)
        put("pos", s.verticalPosition)
        put("edge", s.edge.name)
        put("edgeColor", s.edgeColor)
    }.toString()

    fun decode(text: String?): SubtitleStyle {
        if (text.isNullOrBlank()) return SubtitleStyle.DEFAULT
        val o: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return SubtitleStyle.DEFAULT
        val d = SubtitleStyle.DEFAULT
        fun <T> get(k: String, f: (kotlinx.serialization.json.JsonPrimitive) -> T, def: T): T =
            runCatching { o[k]?.jsonPrimitive?.let(f) }.getOrNull() ?: def
        return SubtitleStyle(
            textColor = get("textColor", { it.int }, d.textColor),
            backgroundEnabled = get("bg", { it.boolean }, d.backgroundEnabled),
            backgroundOpacity = get("bgOpacity", { it.float }, d.backgroundOpacity).coerceIn(0f, 1f),
            sizeScale = get("size", { it.float }, d.sizeScale).coerceIn(SubtitleStyle.MIN_SCALE, SubtitleStyle.MAX_SCALE),
            verticalPosition = get("pos", { it.float }, d.verticalPosition).coerceIn(SubtitleStyle.TOP, SubtitleStyle.BOTTOM),
            edge = get("edge", { p -> SubtitleEdge.entries.firstOrNull { it.name == p.content } }, null) ?: d.edge,
            edgeColor = get("edgeColor", { it.int }, d.edgeColor),
        )
    }
}
