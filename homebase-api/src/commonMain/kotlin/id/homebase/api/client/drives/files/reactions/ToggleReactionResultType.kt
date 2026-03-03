package id.homebase.api.client.drives.files.reactions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ToggleReactionResultType {

    None,

    @SerialName("added")
    Added,

    @SerialName("deleted")
    Deleted
}