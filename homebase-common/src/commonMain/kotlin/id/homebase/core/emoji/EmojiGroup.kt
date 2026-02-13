package id.homebase.core.emoji

import kotlinx.serialization.Serializable

@Serializable
data class EmojiGroup(
    val id: Int,
    val name: String   // e.g., "smileys-emotion"
)

@Serializable
data class EmojiGroupData(
    val groups: Map<Int, String>,
)