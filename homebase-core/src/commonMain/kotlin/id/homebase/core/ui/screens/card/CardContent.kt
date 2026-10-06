@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.core.ui.screens.profile.LoadedProfileAttributes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.isOnCard
import id.homebase.core.ui.screens.profile.profileNameValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class CardContentItem(
    val id: Uuid,
    val type: String,
    val text: String,
    val audience: ProfileAudience,
    val shown: Boolean,
    val locked: Boolean,
    // The Public card can't render without a name, so it can't be switched off there.
    val required: Boolean = false,
)

internal val CARD_SOCIAL_TYPES = listOf(
    ProfileAttributeTypes.TWITTER,
    ProfileAttributeTypes.FACEBOOK,
    ProfileAttributeTypes.INSTAGRAM,
    ProfileAttributeTypes.TIKTOK,
    ProfileAttributeTypes.LINKEDIN,
)

private val LEADING_TYPES = listOf(
    ProfileAttributeTypes.NAME,
    ProfileAttributeTypes.BIO_SUMMARY,
    ProfileAttributeTypes.PHONE,
    ProfileAttributeTypes.EMAIL,
)

internal fun CardAudience.shows(audience: ProfileAudience): Boolean = when (this) {
    CardAudience.Public -> audience == ProfileAudience.Public
    is CardAudience.Circle -> audience.isOnCard(id)
}

internal fun CardAudience.newItemAudience(): ProfileAudience = when (this) {
    CardAudience.Public -> ProfileAudience.Public
    is CardAudience.Circle -> ProfileAudience.Circles(setOf(id))
}

internal fun ProfileAudience.shownOn(card: CardAudience, on: Boolean, previous: ProfileAudience?): ProfileAudience? = when (card) {
    CardAudience.Public -> when {
        on -> ProfileAudience.Public.takeIf { this != ProfileAudience.Public }
        this != ProfileAudience.Public -> null
        else -> previous?.takeIf { it != ProfileAudience.Public && it.isSavable } ?: ProfileAudience.OnlyMe
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
            id = attribute.id,
            type = attribute.type,
            text = text,
            audience = audience,
            shown = card.shows(audience),
            locked = card is CardAudience.Circle && audience == ProfileAudience.Public,
            required = card == CardAudience.Public && attribute.type == ProfileAttributeTypes.NAME && card.shows(audience),
        )
    }
    return LEADING_TYPES.mapNotNull { type -> loaded.byType[type]?.let { item(it, contentText(it)) } } +
        loaded.links.mapNotNull { link ->
            item(link, link.string(ProfileAttributeTypes.KEY_LINK_TEXT)?.ifBlank { null } ?: link.string(ProfileAttributeTypes.KEY_LINK_TARGET))
        } +
        CARD_SOCIAL_TYPES.mapNotNull { type -> loaded.byType[type]?.let { item(it, contentText(it)) } }
}

private fun contentText(attribute: ProfileAttribute): String? {
    val fields = ProfileEditViewModel.TYPE_FIELDS[attribute.type].orEmpty()
    val values = fields.associate { (field, key) -> field to attribute.string(key).orEmpty() }
    return when (attribute.type) {
        ProfileAttributeTypes.NAME -> profileNameValue(values)
        ProfileAttributeTypes.PHONE -> values[ProfileField.PHONE]?.ifBlank { null }?.let(::formatPhoneForDisplay)
        else -> fields.firstOrNull()?.first?.let { values[it] }
    }
}
