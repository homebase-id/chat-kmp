package id.homebase.api.client.drives.files.reactions

import kotlinx.serialization.Serializable

@Serializable
data class ToggleReactionResult(
    val resultType: ToggleReactionResultType
)


