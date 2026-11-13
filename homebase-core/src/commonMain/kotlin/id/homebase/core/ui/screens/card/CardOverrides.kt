package id.homebase.core.ui.screens.card

import id.homebase.api.util.strictBoolean
import id.homebase.api.util.strictDouble
import id.homebase.api.util.strictString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mirrors `CardOverrides` in odin-js `cards/overrides.ts`; property order matches the shared golden fixtures. */
@Serializable
data class CardOverrides(
    val palette: CardPalette? = null,
    val type: CardTypeface? = null,
    val portraits: List<CardPortrait>? = null,
    val blocks: List<CardBlock>? = null,
    val socials: String? = null,
    val fields: CardFields? = null,
    val background: String? = null,
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
                        ground = o.strictString("ground"), ink = o.strictString("ink"), muted = o.strictString("muted"),
                        accent = o.strictString("accent"), surface = o.strictString("surface"), surfaceInk = o.strictString("surfaceInk"),
                    )
                },
                type = json.field("type") { o ->
                    CardTypeface(
                        display = o.strictString("display"), text = o.strictString("text"),
                        label = o.strictString("label"), displayCase = o.strictString("displayCase"),
                    )
                },
                portraits = (json["portraits"] as? JsonArray)?.mapNotNull { el ->
                    (el as? JsonObject)?.let { o ->
                        CardPortrait(
                            source = o.strictString("source"), shape = o.strictString("shape"), ring = o.strictDouble("ring"),
                            shadow = o.strictString("shadow"), tilt = o.strictDouble("tilt"), tape = o.strictBoolean("tape"), mono = o.strictBoolean("mono"),
                        )
                    }
                },
                blocks = (json["blocks"] as? JsonArray)?.mapNotNull { el ->
                    val o = el as? JsonObject
                    o?.strictString("kind")?.let { CardBlock(it, o.strictString("presentation")) }
                },
                socials = json.strictString("socials"),
                fields = json.field("fields") { o -> CardFields(order = o.texts("order"), hidden = o.texts("hidden"), hiddenSections = o.texts("hiddenSections")) },
                background = CardBackgrounds.known(json.strictString("background")),
            )

        private fun <T> JsonObject.field(key: String, read: (JsonObject) -> T): T? =
            (this[key] as? JsonObject)?.let(read)

        private fun JsonObject.texts(key: String): List<String>? =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

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

@Serializable
data class CardFields(val order: List<String>? = null, val hidden: List<String>? = null, val hiddenSections: List<String>? = null)
