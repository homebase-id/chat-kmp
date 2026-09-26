package id.homebase.core.ui.screens.card

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object CardDesign {
    const val POSTER = "poster"
    const val BOARD = "board"
    const val COLLAGE = "collage"
    const val DOSSIER = "dossier"
    val all = listOf(POSTER, BOARD, COLLAGE, DOSSIER)

    // Native copy of each page's base colour, shown until the page can be probed so the first frame already matches.
    fun baseArgb(design: String): Int = when (design) {
        POSTER -> 0xFF14120F
        COLLAGE -> 0xFFF3EADB
        DOSSIER -> 0xFF0E1013
        else -> 0xFF1F4E8C
    }.toInt()
}

@Serializable
data class CardPayload(
    val design: String,
    val data: CardData,
)

@Serializable
data class CardData(
    val odinId: String,
    val firstName: String? = null,
    val surName: String? = null,
    val displayName: String? = null,
    val headline: String? = null,
    val bio: String? = null,
    val photo: CardImage? = null,
    val header: CardImage? = null,
    val links: List<CardLink> = emptyList(),
    val socials: List<CardSocial> = emptyList(),
    val posts: List<CardPost> = emptyList(),
)

@Serializable
data class CardImage(val src: String)

@Serializable
data class CardLink(val id: String, val text: String, val target: String)

@Serializable
data class CardSocial(val type: String, val username: String)

@Serializable
data class CardPost(
    val id: String,
    val href: String,
    val date: Long,
    val title: String? = null,
    val excerpt: String? = null,
    val minutes: Double? = null,
    val type: String? = null,
    val image: CardImage? = null,
)

sealed interface CardEvent {
    data object Loaded : CardEvent
    data class Ready(val layout: String, val ms: Long) : CardEvent
    data class Link(val href: String) : CardEvent
    data class Png(val base64: String, val width: Int, val height: Int) : CardEvent
    data class Error(val message: String, val unsupported: Boolean = false) : CardEvent
    data class Edges(val topArgb: Int?, val bottomArgb: Int?) : CardEvent
    data object Painted : CardEvent
}

internal sealed interface CardCommand {
    data class Render(val payload: CardPayload) : CardCommand
    data object ExportPng : CardCommand
    data object ProbeEdges : CardCommand
    data object RequestPaint : CardCommand
}

// Optional fields are omitted rather than sent as null; empty lists are always sent.
internal val cardJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

fun parseCardEvent(json: String): CardEvent? {
    val event = try {
        cardJson.parseToJsonElement(json) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null
    return when (event.string("type")) {
        "loaded" -> CardEvent.Loaded
        "ready" -> CardEvent.Ready(
            layout = event.string("layout") ?: return null,
            ms = event.long("ms") ?: return null,
        )
        "link" -> CardEvent.Link(event.string("href") ?: return null)
        "png" -> CardEvent.Png(
            base64 = event.string("base64") ?: return null,
            width = event.long("width")?.toInt() ?: return null,
            height = event.long("height")?.toInt() ?: return null,
        )
        "error" -> CardEvent.Error(event.string("message") ?: return null)
        "hostEdges" -> CardEvent.Edges(
            topArgb = event.string("top")?.let(::parseCssColor),
            bottomArgb = event.string("bottom")?.let(::parseCssColor),
        )
        "hostPainted" -> CardEvent.Painted
        else -> null
    }
}

internal fun CardPayload.toJson(): String = cardJson.encodeToString(CardPayload.serializer(), this)

internal fun CardCommand.script(): String = when (this) {
    is CardCommand.Render -> "window.homebaseCard.render(${payload.toJson()})"
    CardCommand.ExportPng -> "window.homebaseCard.exportPng()"
    CardCommand.ProbeEdges -> PROBE_EDGES_SCRIPT
    CardCommand.RequestPaint -> REQUEST_PAINT_SCRIPT
}

// Host-side only: reads the colour of the full-width surface at the page's top and bottom edges (not a card that happens to sit there), so native chrome can continue it.
private const val PROBE_EDGES_SCRIPT =
    "(function(){function c(y){var e=document.elementsFromPoint(innerWidth/2,y);" +
        "for(var i=0;i<e.length;i++){var b=getComputedStyle(e[i]).backgroundColor;" +
        "if(e[i].getBoundingClientRect().width<innerWidth-1)continue;" +
        "if(b&&b!=='transparent'&&!/^rgba\\(.*,\\s*0\\)${'$'}/.test(b))return b}return null}" +
        "window.homebaseCardHost.post(JSON.stringify({type:'hostEdges',top:c(1),bottom:c(innerHeight-1)}))})()"

// Two animation frames: the first commits pending layout, the second runs after it has been drawn.
private const val REQUEST_PAINT_SCRIPT =
    "requestAnimationFrame(function(){requestAnimationFrame(function(){" +
        "window.homebaseCardHost.post(JSON.stringify({type:'hostPainted'}))})})"

private val CSS_RGB = Regex("""rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\s*\)""")

internal fun parseCssColor(css: String): Int? {
    val match = CSS_RGB.matchEntire(css.trim()) ?: return null
    val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
    if (alpha < 1.0) return null
    val (r, g, b) = match.groupValues.subList(1, 4).map { it.toInt().coerceIn(0, 255) }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

// Installed before any page script runs, so the page's boot-time `loaded` isn't lost.
internal fun bridgeShim(postMessageFunction: String): String =
    "window.homebaseCardHost=window.homebaseCardHost||{post:function(m){$postMessageFunction(m)}};"

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

// performance.now() deltas arrive fractional.
private fun JsonObject.long(key: String): Long? {
    val primitive = (this[key] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.roundToLong()
}
