package id.homebase.chat.chatappearance.ui

import androidx.compose.runtime.staticCompositionLocalOf
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper

val LocalActiveChatColor = staticCompositionLocalOf<ChatColor> { ChatColorPresets.default }
val LocalActiveWallpaper = staticCompositionLocalOf<ChatWallpaper> { ChatWallpaper.None }
val LocalWallpaperDimInDarkTheme = staticCompositionLocalOf { true }
