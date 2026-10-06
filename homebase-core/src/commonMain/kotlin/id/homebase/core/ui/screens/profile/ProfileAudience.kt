package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.util.compareStringUuId
import id.homebase.core.ui.screens.card.CardCircle

/** Who sees one profile detail; it decides which cards show it. */
sealed interface ProfileAudience {
    data object Public : ProfileAudience
    data object OnlyMe : ProfileAudience

    /** Ids are taken from the Contacts-app circle list, never from the server's spelling. */
    data class Circles(val ids: Set<String>) : ProfileAudience

    val isSavable: Boolean get() = this !is Circles || ids.isNotEmpty()

    val visibility: ProfileVisibility
        get() = when (this) {
            Public -> ProfileVisibility.ANONYMOUS
            OnlyMe -> ProfileVisibility.OWNER
            is Circles -> ProfileVisibility.CONNECTED
        }

    val circleIds: List<String> get() = (this as? Circles)?.ids?.sorted().orEmpty()
}

/** Connected with no circles means every connection, so it reads as every Contacts circle selected. */
internal fun ProfileAttribute.audience(circles: List<CardCircle>): ProfileAudience = when (visibility) {
    ProfileVisibility.ANONYMOUS -> ProfileAudience.Public
    ProfileVisibility.OWNER -> ProfileAudience.OnlyMe
    ProfileVisibility.CONNECTED, ProfileVisibility.AUTHENTICATED -> {
        val stored = acl.circleIdList.orEmpty()
        if (stored.isEmpty() || stored.any { compareStringUuId(it, CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE) }) {
            ProfileAudience.Circles(circles.map { it.id }.toSet())
        } else {
            ProfileAudience.Circles(circles.filter { c -> stored.any { compareStringUuId(it, c.id) } }.map { it.id }.toSet())
        }
    }
}

internal fun ProfileAudience.toAcl(): AccessControlList =
    AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circleIds.takeIf { it.isNotEmpty() })
