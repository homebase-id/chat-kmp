package id.homebase.core.ui.screens.card

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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

        // Mirrors the web's applyOverrides: an undecodable value drops only itself, never its siblings.
        fun fromJson(json: JsonObject): CardOverrides =
            CardOverrides(
                palette = json.field("palette") { o ->
                    CardPalette(
                        ground = o.text("ground"), ink = o.text("ink"), muted = o.text("muted"),
                        accent = o.text("accent"), surface = o.text("surface"), surfaceInk = o.text("surfaceInk"),
                    )
                },
                type = json.field("type") { o ->
                    CardTypeface(
                        display = o.text("display"), text = o.text("text"),
                        label = o.text("label"), displayCase = o.text("displayCase"),
                    )
                },
                portraits = (json["portraits"] as? JsonArray)?.mapNotNull { el ->
                    (el as? JsonObject)?.let { o ->
                        CardPortrait(
                            source = o.text("source"), shape = o.text("shape"), ring = o.number("ring"),
                            shadow = o.text("shadow"), tilt = o.number("tilt"), tape = o.flag("tape"), mono = o.flag("mono"),
                        )
                    }
                },
                blocks = (json["blocks"] as? JsonArray)?.mapNotNull { el ->
                    val o = el as? JsonObject
                    o?.text("kind")?.let { CardBlock(it, o.text("presentation")) }
                },
                socials = json.text("socials"),
            )

        private fun <T> JsonObject.field(key: String, read: (JsonObject) -> T): T? =
            (this[key] as? JsonObject)?.let(read)

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}

/** A JSON number the web treats as a plain `number`; whole values stay integers on the wire so a read-save round trip is byte-stable. */
object CardNumberSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("CardNumber", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
    override fun serialize(encoder: Encoder, value: Double) {
        val whole = value.toLong()
        if (whole.toDouble() == value) encoder.encodeLong(whole) else encoder.encodeDouble(value)
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
    @Serializable(with = CardNumberSerializer::class) val ring: Double? = null,
    val shadow: String? = null,
    @Serializable(with = CardNumberSerializer::class) val tilt: Double? = null,
    val tape: Boolean? = null,
    val mono: Boolean? = null,
)

@Serializable
data class CardBlock(val kind: String, val presentation: String? = null)
