package id.homebase.api.client.invitations

import kotlinx.serialization.Serializable

/** `GET /api/v2/invitation-codes`. Empty when the identity has no code or the host has invitations off. */
@Serializable
data class InvitationCodesResponse(
    val codes: List<InvitationCode> = emptyList(),
)

@Serializable
data class InvitationCode(
    val code: String,
    val maxUses: Int = 0,
    val usesLeft: Int = 0,
    /** Built by the server; never assemble this URL on the client. */
    val link: String,
)
