package id.homebase.api.client.drives.files.reactions

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class ToggleReactionRequest(
    val reaction: String,
    val transitOptions: ReactionTransitOptions
)