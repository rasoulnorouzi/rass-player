package nl.rass.player.player.subtitle.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal fun parseJsonObject(text: String): JsonObject = try {
    Json.parseToJsonElement(text) as? JsonObject
        ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "پاسخ سرویس زیرنویس نامعتبر است.")
} catch (e: kotlinx.serialization.SerializationException) {
    throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "پاسخ سرویس زیرنویس نامعتبر است.", e)
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonElement.asObj(): JsonObject? = this as? JsonObject
