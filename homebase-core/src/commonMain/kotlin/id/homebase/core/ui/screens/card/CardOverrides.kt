package id.homebase.core.ui.screens.card

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Mirrors `CardOverrides` in odin-js `cards/overrides.ts`; property order matches the shared golden fixtures. */
@Serializable
data class CardOverrides(
    val palette: CardPalette? = null,
    val type: CardTypeface? = null,
    val portraits: List<CardPortrait>? = null,
    val blocks: List<CardBlock>? = null,
    val socials: String? = null,
) {
    fun isEmpty(): Boolean = this == EMPTY

    fun toJson(): JsonObject = cardJson.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        val EMPTY = CardOverrides()

        private val lenient = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // A stored value this build cannot decode reads as empty, so the next save rewrites it without the unreadable part.
        fun fromJson(json: JsonObject): CardOverrides =
            try {
                lenient.decodeFromJsonElement(serializer(), json)
            } catch (e: SerializationException) {
                EMPTY
            } catch (e: IllegalArgumentException) {
                EMPTY
            }
    }
}

@Serializable
data class CardPalette(
    val ground: String? = null,
    val ink: String? = null,
    val muted: String? = null,
    val accent: String? = null,
    val surface: String? = null,
    val surfaceInk: String? = null,
)

@Serializable
data class CardTypeface(
    val display: String? = null,
    val text: String? = null,
    val label: String? = null,
    val displayCase: String? = null,
)

@Serializable
data class CardPortrait(
    val source: String? = null,
    val shape: String? = null,
    val ring: Int? = null,
    val shadow: String? = null,
    val tilt: Int? = null,
    val tape: Boolean? = null,
    val mono: Boolean? = null,
)

@Serializable
data class CardBlock(val kind: String, val presentation: String? = null)
