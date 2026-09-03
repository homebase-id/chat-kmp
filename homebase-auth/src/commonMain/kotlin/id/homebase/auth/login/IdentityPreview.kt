package id.homebase.auth.login

import androidx.compose.runtime.Immutable
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.common.OdinId

// Names stay nullable: /pub/profile 404s for a real identity that never published one, so a
// preview must be able to render from the domain alone and fill in later, or never.
@Immutable
data class IdentityPreview(
    val odinId: OdinId,
    val displayName: String? = null,
    val status: String? = null,
)

internal fun ProfileCard.toPreview(odinId: OdinId): IdentityPreview = IdentityPreview(
    odinId = odinId,
    displayName = name.takeIf { it.isNotBlank() },
    status = status?.takeIf { it.isNotBlank() },
)
