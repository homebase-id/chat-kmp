package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.isVisibleToCircle
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.util.compareStringUuId
import id.homebase.core.ui.screens.profile.AclFilter
import id.homebase.core.ui.screens.profile.aclFilter
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val PUBLIC_CARD_PRIORITY = 1000

private val FIXED_CIRCLE_PRIORITIES = mapOf("family" to 10, "friends" to 20, "work" to 30)
private const val OTHER_CIRCLE_PRIORITY = 40

/** Family, Friends and Work keep fixed slots so a viewer in several circles gets the closest card; any other circle follows in name order. */
internal fun fixedCirclePriorities(circles: List<CardCircle>): Map<String, Int> {
    val others = circles.filter { it.name.trim().lowercase() !in FIXED_CIRCLE_PRIORITIES }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id.lowercase() }))
    return buildMap {
        circles.forEach { c -> FIXED_CIRCLE_PRIORITIES[c.name.trim().lowercase()]?.let { put(c.id, it) } }
        others.forEachIndexed { i, c -> put(c.id, OTHER_CIRCLE_PRIORITY + i) }
    }
}

private const val KEY_DESIGN = "design"
private const val KEY_OVERRIDES = "overrides"

sealed interface CardAudience {
    data object Public : CardAudience
    data class Circle(val id: String, val label: String) : CardAudience
}

// The server may spell a circle id differently from the circle list it came from.
internal fun CardAudience.isSameAs(other: CardAudience): Boolean =
    this == other || (this is CardAudience.Circle && other is CardAudience.Circle && compareStringUuId(id, other.id))

internal fun CardAudience.aclFilter(): AclFilter = when (this) {
    CardAudience.Public -> ProfileVisibility.ANONYMOUS.aclFilter()
    is CardAudience.Circle -> { acl -> acl.isVisibleToCircle(id) }
}

/** [design] stays a raw string so a design this build doesn't know survives a read-modify-write; [extra] keeps unknown `data` keys for the same reason. */
data class ProfileCard(
    val id: Uuid,
    val versionTag: Uuid,
    val audience: CardAudience,
    val design: String,
    val overrides: CardOverrides = CardOverrides.EMPTY,
    val priority: Int = PUBLIC_CARD_PRIORITY,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /** A null [overrides] keeps this card's; either way a design change drops what the new design doesn't expose. */
    fun withDesign(design: String, overrides: CardOverrides? = null): ProfileCard {
        val kept = overrides ?: this.overrides
        return copy(design = design, overrides = if (this.design != design) kept.prunedFor(design) else kept)
    }

    fun toData(): JsonObject = JsonObject(
        buildMap {
            putAll(extra)
            put(KEY_DESIGN, JsonPrimitive(design))
            if (!overrides.isEmpty()) put(KEY_OVERRIDES, overrides.toJson())
            if (audience is CardAudience.Circle) put(ProfileAttributeTypes.KEY_LABEL, JsonPrimitive(audience.label))
        },
    )

    companion object {
        fun from(attribute: ProfileAttribute): ProfileCard? {
            if (attribute.type != ProfileAttributeTypes.PROFILE_CARD) return null
            val design = attribute.string(KEY_DESIGN) ?: return null
            val circles = attribute.acl.circleIdList.orEmpty()
            val audience = if (attribute.visibility == ProfileVisibility.CONNECTED && circles.size == 1) {
                CardAudience.Circle(circles.single(), attribute.string(ProfileAttributeTypes.KEY_LABEL).orEmpty())
            } else if (attribute.visibility == ProfileVisibility.ANONYMOUS) {
                CardAudience.Public
            } else {
                return null
            }
            val known = setOf(KEY_DESIGN, KEY_OVERRIDES, ProfileAttributeTypes.KEY_LABEL)
            return ProfileCard(
                id = attribute.id,
                versionTag = attribute.versionTag,
                audience = audience,
                design = design,
                overrides = (attribute.data[KEY_OVERRIDES] as? JsonObject)?.let(CardOverrides::fromJson) ?: CardOverrides.EMPTY,
                priority = attribute.priority,
                extra = JsonObject(attribute.data.filterKeys { it !in known }),
            )
        }
    }
}

fun List<ProfileAttribute>.profileCards(): List<ProfileCard> = mapNotNull(ProfileCard::from)

fun List<ProfileCard>.publicCard(): ProfileCard? = firstOrNull { it.audience is CardAudience.Public }
