package id.homebase.chat.chatappearance.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatColor {
    val id: String

    @Serializable
    @SerialName("auto")
    data object Auto : ChatColor {
        override val id: String = "auto"
    }

    @Serializable
    @SerialName("not_set")
    data object NotSet : ChatColor {
        override val id: String = "not_set"
    }

    @Serializable
    @SerialName("solid")
    data class Solid(
        override val id: String,
        val colorArgb: Long,
    ) : ChatColor

    @Serializable
    @SerialName("gradient")
    data class Gradient(
        override val id: String,
        val colorsArgb: List<Long>,
        val angleDegrees: Float,
    ) : ChatColor
}
