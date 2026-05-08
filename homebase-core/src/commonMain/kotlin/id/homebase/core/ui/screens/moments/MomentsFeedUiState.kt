package id.homebase.core.ui.screens.moments

import id.homebase.core.moments.services.MomentFeedItem

data class MomentsFeedUiState(
    val moments: List<MomentFeedItem> = emptyList(),
)
