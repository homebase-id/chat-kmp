package id.homebase.chat.chatappearance.data

import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatColorsMapper
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.model.ChatWallpaperData
import id.homebase.core.settings.UserPreferences
import kotlinx.serialization.json.Json

class ChatAppearanceRepository(
    private val userPreferences: UserPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getGlobalChatColor(): ChatColor {
        val id = userPreferences.globalChatColorId
        if (id == "auto") return ChatColor.Auto
        return ChatColorPresets.findById(id) ?: ChatColor.Auto
    }

    fun setGlobalChatColor(color: ChatColor) {
        userPreferences.globalChatColorId = color.id
    }

    fun resetGlobalChatColor() {
        userPreferences.globalChatColorId = "auto"
    }

    fun getGlobalWallpaper(): ChatWallpaper {
        val jsonStr = userPreferences.globalWallpaperJson
        if (jsonStr.isBlank()) return ChatWallpaper.None
        return try {
            val data = json.decodeFromString(ChatWallpaperData.serializer(), jsonStr)
            ChatWallpaperData.toWallpaper(data)
        } catch (_: Throwable) {
            ChatWallpaper.None
        }
    }

    fun setGlobalWallpaper(wallpaper: ChatWallpaper) {
        val data = ChatWallpaperData.from(wallpaper)
        userPreferences.globalWallpaperJson = if (data != null) {
            json.encodeToString(ChatWallpaperData.serializer(), data)
        } else {
            ""
        }
    }

    fun resetGlobalWallpaper() {
        userPreferences.globalWallpaperJson = ""
    }

    fun getGlobalDimInDarkTheme(): Boolean = userPreferences.globalWallpaperDimInDarkTheme

    fun setGlobalDimInDarkTheme(enabled: Boolean) {
        userPreferences.globalWallpaperDimInDarkTheme = enabled
    }

    fun resolveAutoColor(wallpaper: ChatWallpaper): ChatColor = ChatColorsMapper.resolve(wallpaper)

    fun resolveEffectiveColor(conversationColorId: String?, wallpaper: ChatWallpaper): ChatColor {
        val id = conversationColorId ?: userPreferences.globalChatColorId
        if (id == "auto") return resolveAutoColor(wallpaper)
        return ChatColorPresets.findById(id) ?: ChatColor.Auto
    }

    fun resolveEffectiveWallpaper(conversationWallpaper: ChatWallpaperData?): ChatWallpaper {
        if (conversationWallpaper != null) return ChatWallpaperData.toWallpaper(conversationWallpaper)
        return getGlobalWallpaper()
    }
}
