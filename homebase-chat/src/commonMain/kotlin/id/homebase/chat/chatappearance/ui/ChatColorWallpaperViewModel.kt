package id.homebase.chat.chatappearance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.chatappearance.data.ChatAppearanceRepository
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.model.ChatWallpaperPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatColorWallpaperViewModel(
    private val repository: ChatAppearanceRepository,
    private val conversationId: String? = null,
) : ViewModel() {

    data class UiState(
        val activeChatColor: ChatColor = ChatColor.Auto,
        val activeWallpaper: ChatWallpaper = ChatWallpaper.None,
        val dimInDarkTheme: Boolean = true,
        val isPerConversation: Boolean = false,
        val allBubbleColors: List<ChatColor> = ChatColorPresets.all,
        val allWallpaperPresets: List<ChatWallpaper> = ChatWallpaperPresets.all,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            activeChatColor = repository.getGlobalChatColor(),
            activeWallpaper = repository.getGlobalWallpaper(),
            dimInDarkTheme = repository.getGlobalDimInDarkTheme(),
            isPerConversation = conversationId != null,
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setChatColor(color: ChatColor) {
        viewModelScope.launch {
            repository.setGlobalChatColor(color)
            _uiState.update { it.copy(activeChatColor = color) }
        }
    }

    fun setWallpaper(wallpaper: ChatWallpaper) {
        viewModelScope.launch {
            repository.setGlobalWallpaper(wallpaper)
            _uiState.update { it.copy(activeWallpaper = wallpaper) }
        }
    }

    fun setDimInDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            repository.setGlobalDimInDarkTheme(enabled)
            _uiState.update { it.copy(dimInDarkTheme = enabled) }
        }
    }

    fun resetChatColors() {
        viewModelScope.launch {
            repository.resetGlobalChatColor()
            _uiState.update { it.copy(activeChatColor = ChatColor.Auto) }
        }
    }

    fun resetWallpapers() {
        viewModelScope.launch {
            repository.resetGlobalWallpaper()
            _uiState.update { it.copy(activeWallpaper = ChatWallpaper.None) }
        }
    }
}
