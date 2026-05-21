package id.homebase.core.ui.screens.moments

import id.homebase.api.client.auth.OwnerSession
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.moments.services.MomentFeedItem

data class MomentsFeedUiState(
    val moments: List<MomentFeedItem> = emptyList(),
    val ownerSession: OwnerSession? = null,
    val connectionStatus: AppConnectionStatus = AppConnectionStatus.Connecting,
    val driveIsSyncing: Boolean = false,
    val hasDriveError: Boolean = false,
)
