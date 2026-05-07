package id.homebase.chat.chatappearance.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatWallpaperData(
    val type: String,
    val id: String,
    val colorArgb: Long? = null,
    val colorsArgb: List<Long>? = null,
    val positions: List<Float>? = null,
    val angleDegrees: Float? = null,
    val payloadKey: String? = null,
) {
    companion object {
        fun from(wallpaper: ChatWallpaper): ChatWallpaperData? = when (wallpaper) {
            is ChatWallpaper.None -> null
            is ChatWallpaper.SolidColor -> ChatWallpaperData(
                type = "solid_color",
                id = wallpaper.id,
                colorArgb = wallpaper.colorArgb,
            )

            is ChatWallpaper.GradientColor -> ChatWallpaperData(
                type = "gradient_color",
                id = wallpaper.id,
                colorsArgb = wallpaper.colorsArgb,
                positions = wallpaper.positions,
                angleDegrees = wallpaper.angleDegrees,
            )

            is ChatWallpaper.Photo -> ChatWallpaperData(
                type = "photo",
                id = wallpaper.id,
                payloadKey = wallpaper.payloadKey,
            )
        }

        fun toWallpaper(data: ChatWallpaperData?): ChatWallpaper {
            if (data == null) return ChatWallpaper.None
            return when (data.type) {
                "solid_color" -> ChatWallpaperPresets.findById(data.id)
                    ?: ChatWallpaper.SolidColor(
                        id = data.id,
                        colorArgb = data.colorArgb ?: 0,
                    )

                "gradient_color" -> ChatWallpaperPresets.findById(data.id)
                    ?: ChatWallpaper.GradientColor(
                        id = data.id,
                        colorsArgb = data.colorsArgb ?: emptyList(),
                        positions = data.positions ?: emptyList(),
                        angleDegrees = data.angleDegrees ?: 180f,
                    )

                "photo" -> ChatWallpaper.Photo(
                    id = data.id,
                    payloadKey = data.payloadKey ?: "chat_wlpr",
                )

                else -> ChatWallpaper.None
            }
        }
    }
}
