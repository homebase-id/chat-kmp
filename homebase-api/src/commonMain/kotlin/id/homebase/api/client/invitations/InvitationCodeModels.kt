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

/** A code as the provisioning host's create and list calls return it (PROTOTYPE, #1908). */
@Serializable
data class OwnedInvitationCode(
    val code: String,
    val owner: String,
    val maxUses: Int = 0,
    val usesLeft: Int = 0,
)

@Serializable
internal data class CreateInvitationCodeRequest(
    val owner: String,
    val maxUses: Int,
)
