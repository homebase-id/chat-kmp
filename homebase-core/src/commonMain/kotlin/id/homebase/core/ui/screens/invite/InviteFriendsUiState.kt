package id.homebase.core.ui.screens.invite

import id.homebase.api.client.invitations.InvitationCode

data class InviteFriendsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val codes: List<InvitationCode> = emptyList(),
) {
    val hasNoCode: Boolean get() = !isLoading && !loadFailed && codes.isEmpty()
}

val InvitationCode.isUsedUp: Boolean get() = usesLeft <= 0

sealed interface InviteFriendsUiAction {
    data object Retry : InviteFriendsUiAction
}
