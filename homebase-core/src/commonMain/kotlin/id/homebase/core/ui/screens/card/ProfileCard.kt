package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val PUBLIC_CARD_PRIORITY = 1000

private const val KEY_DESIGN = "design"
private const val KEY_OVERRIDES = "overrides"

sealed interface CardAudience {
    data object Public : CardAudience
    data class Circle(val id: String, val label: String) : CardAudience
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
