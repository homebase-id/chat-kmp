package id.homebase.api.client.onboarding

import kotlinx.serialization.Serializable

/**
 * `GET /api/v2/onboarding` (#1908): what signup knew about this identity. All null for an identity with
 * none. Times are unix milliseconds.
 */
@Serializable
data class OnboardingInfo(
    /** The OdinId whose invitation code this identity signed up with. */
    val invitedBy: String? = null,
    val invitationCode: String? = null,
    val signedUpAt: Long? = null,
    /** Set once, for the whole identity, when Chat has acted on this. */
    val handledAt: Long? = null,
)
