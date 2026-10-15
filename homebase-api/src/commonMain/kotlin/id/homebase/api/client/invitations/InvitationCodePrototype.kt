package id.homebase.api.client.invitations

/**
 * PROTOTYPE (#1908) — hard-coded for bleeding edge only, matching odin-core's `InvitationCodePrototype`.
 * Creating and listing any identity's codes takes only this key, so it must be replaced with a real
 * credential before this goes anywhere else.
 */
object InvitationCodePrototype {
    const val BLEEDING_EDGE_PROVISIONING_DOMAIN = "provisioning.demo.dominion.id"
    const val API_KEY_HEADER = "Odin-Invitation-Code-Key"
    const val API_KEY = "UiRtjjN0weyVWWym-B_AZzoJUhzkG7tfF5dTw6ZLBYA"
}
