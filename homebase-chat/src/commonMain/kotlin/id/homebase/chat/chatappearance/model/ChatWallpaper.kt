package id.homebase.chat.chatappearance.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatWallpaper {
    val id: String

    @Serializable
    @SerialName("none")
    data object None : ChatWallpaper {
        override val id: String = "none"
    }

    @Serializable
    @SerialName("solid_color")
    data class SolidColor(
        override val id: String,
        val colorArgb: Long,
    ) : ChatWallpaper

    @Serializable
    @SerialName("gradient_color")
    data class GradientColor(
        override val id: String,
        val colorsArgb: List<Long>,
        val positions: List<Float>,
        val angleDegrees: Float,
    ) : ChatWallpaper

    @Serializable
    @SerialName("photo")
    data class Photo(
        override val id: String,
        val payloadKey: String = "chat_wlpr",
    ) : ChatWallpaper
}
