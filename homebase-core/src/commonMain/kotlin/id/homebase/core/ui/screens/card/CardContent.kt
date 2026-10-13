@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.core.ui.screens.profile.LoadedProfileAttributes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.isOnCard
import id.homebase.core.ui.screens.profile.profileNameValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import id.homebase.core.ui.screens.profile.PROFILE_TYPE_FIELDS
import id.homebase.core.ui.screens.profile.formatBirthday
import id.homebase.core.ui.screens.profile.profileAddressValue

internal data class CardContentItem(
    val id: Uuid,
    val type: String,
    val text: String,
    val audience: ProfileAudience,
    val shown: Boolean,
    val locked: Boolean,
    // The Public card can't render without a name, so it can't be switched off there.
    val required: Boolean = false,
    val data: Map<String, String> = emptyMap(),
    // Older records of the same type with other data; the editor shows them so the user can remove them.
    val conflicts: List<ProfileAttribute> = emptyList(),
) {
    val everyCard: Boolean get() = audience == ProfileAudience.Public
}

internal val CARD_SOCIAL_TYPES = listOf(
    ProfileAttributeTypes.TWITTER,
    ProfileAttributeTypes.FACEBOOK,
    ProfileAttributeTypes.INSTAGRAM,
    ProfileAttributeTypes.TIKTOK,
    ProfileAttributeTypes.LINKEDIN,
)

// Each add makes a new record, so the profile can hold several.
internal val REPEATABLE_TYPES = setOf(ProfileAttributeTypes.LINK, ProfileAttributeTypes.PHONE, ProfileAttributeTypes.EMAIL)

private val LEADING_TYPES = listOf(
    ProfileAttributeTypes.NAME,
    ProfileAttributeTypes.NICKNAME,
    ProfileAttributeTypes.STATUS,
    ProfileAttributeTypes.BIO_SUMMARY,
    ProfileAttributeTypes.PHONE,
    ProfileAttributeTypes.EMAIL,
    ProfileAttributeTypes.BIRTHDAY,
    ProfileAttributeTypes.ADDRESS,
)

internal fun CardAudience.shows(audience: ProfileAudience): Boolean = when (this) {
    CardAudience.Public -> audience == ProfileAudience.Public
    is CardAudience.Circle -> audience.isOnCard(id)
}

internal fun CardAudience.newItemAudience(): ProfileAudience = when (this) {
    CardAudience.Public -> ProfileAudience.Public
    is CardAudience.Circle -> ProfileAudience.Circles(setOf(id))
}

// Hiding from one card never takes an item off the others: off the Public card it stays with every circle.
internal fun ProfileAudience.shownOn(
    card: CardAudience,
    on: Boolean,
    previous: ProfileAudience?,
    circles: List<CardCircle> = emptyList(),
): ProfileAudience? = when (card) {
    CardAudience.Public -> when {
        on -> ProfileAudience.Public.takeIf { this != ProfileAudience.Public }
        this != ProfileAudience.Public -> null
        else -> previous?.takeIf { it != ProfileAudience.Public && it.isSavable }
            ?: circles.takeIf { it.isNotEmpty() }?.let { ProfileAudience.Circles(it.map { c -> c.id }.toSet()) }
            ?: ProfileAudience.OnlyMe
    }
    is CardAudience.Circle -> when (this) {
        ProfileAudience.Public -> null
        ProfileAudience.OnlyMe -> if (on) ProfileAudience.Circles(setOf(card.id)) else null
        is ProfileAudience.Circles -> {
            val next = if (on) ids + card.id else ids - card.id
            when {
                next == ids -> null
                next.isEmpty() && otherIds.isEmpty() -> ProfileAudience.OnlyMe
                else -> copy(ids = next)
            }
        }
    }
}

internal fun cardContentItems(attributes: List<ProfileAttribute>, circles: List<CardCircle>, card: CardAudience): List<CardContentItem> {
    val loaded = LoadedProfileAttributes.from(attributes)
    fun item(attribute: ProfileAttribute, text: String?): CardContentItem? {
        if (text.isNullOrBlank()) return null
        val audience = attribute.audience(circles)
        return CardContentItem(
            data = dataOf(attribute),
            conflicts = if (attribute.type in REPEATABLE_TYPES) emptyList() else loaded.conflicts[attribute.type].orEmpty(),
            id = attribute.id,
            type = attribute.type,
            text = text,
            audience = audience,
            shown = card.shows(audience),
            locked = card is CardAudience.Circle && audience == ProfileAudience.Public,
            required = card == CardAudience.Public && attribute.type == ProfileAttributeTypes.NAME && card.shows(audience),
        )
    }
    fun recordsOf(type: String) =
        if (type in REPEATABLE_TYPES) attributes.filter { it.type == type }.sortedBy { it.priority } else listOfNotNull(loaded.byType[type])
    return LEADING_TYPES.flatMap(::recordsOf).mapNotNull { item(it, contentText(it)) } +
        loaded.links.mapNotNull { link ->
            item(link, link.string(ProfileAttributeTypes.KEY_LINK_TEXT)?.ifBlank { null } ?: link.string(ProfileAttributeTypes.KEY_LINK_TARGET))
        } +
        CARD_SOCIAL_TYPES.mapNotNull { type -> loaded.byType[type]?.let { item(it, contentText(it)) } }
}

private val LINK_KEYS = listOf(ProfileAttributeTypes.KEY_LINK_TEXT, ProfileAttributeTypes.KEY_LINK_TARGET)

private fun dataOf(attribute: ProfileAttribute): Map<String, String> =
    (PROFILE_TYPE_FIELDS[attribute.type]?.map { it.second } ?: LINK_KEYS).associateWith { attribute.string(it).orEmpty() }

private fun valuesOf(attribute: ProfileAttribute): Map<ProfileField, String> =
    PROFILE_TYPE_FIELDS[attribute.type].orEmpty().associate { (field, key) -> field to attribute.string(key).orEmpty() }

private fun contentText(attribute: ProfileAttribute): String? {
    val values = valuesOf(attribute)
    return when (attribute.type) {
        ProfileAttributeTypes.NAME -> profileNameValue(values)
        ProfileAttributeTypes.PHONE -> values[ProfileField.PHONE]?.ifBlank { null }?.let(::formatPhoneForDisplay)
        ProfileAttributeTypes.ADDRESS -> profileAddressValue(values)
        ProfileAttributeTypes.BIRTHDAY -> values[ProfileField.BIRTHDAY]?.ifBlank { null }?.let { formatBirthday(it) ?: it }
        else -> PROFILE_TYPE_FIELDS[attribute.type]?.firstOrNull()?.first?.let { values[it] }
    }
}

internal class ImportStep(val attribute: ProfileAttribute, val previous: ProfileAudience, val next: ProfileAudience)

// A card's photo is never copied: widening it onto another card would stop it being scoped to one circle.
internal fun importPlan(attributes: List<ProfileAttribute>, circles: List<CardCircle>, from: CardAudience, into: CardAudience): List<ImportStep> {
    val byId = attributes.associateBy { it.id }
    return cardContentItems(attributes, circles, from)
        .filter { it.shown && !into.shows(it.audience) }
        .mapNotNull { item ->
            val attribute = byId[item.id] ?: return@mapNotNull null
            item.audience.shownOn(into, true, null)?.let { ImportStep(attribute, item.audience, it) }
        }
}
