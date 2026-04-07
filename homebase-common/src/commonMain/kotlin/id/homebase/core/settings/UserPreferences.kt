package id.homebase.core.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserPreferences(private val settings: Settings) {
    private val _preferenceState = MutableStateFlow(
        PreferenceState(
            theme = theme,
        )
    )
    val preferenceState: StateFlow<PreferenceState> = _preferenceState

    var language: String
        get() = settings.getString("language", "system")
        set(value) = settings.putString("language", value)

    var theme: ThemeState
        get() {
            val theme = settings.getString("theme", "System")
            return ThemeState.valueOf(theme)
        }
        set(value) {
            settings.putString("theme", value.name)
            _preferenceState.value = _preferenceState.value.copy(theme = value)
        }

    var preferredUserReactions: List<String>
        get() = settings.getStringOrNull("preferred_user_reactions")?.split(",") ?: listOf()
        set(value) = settings.putString("preferred_user_reactions", value.joinToString(","))

    // Notification preferences
    var playWhileAppOpen: Boolean
        get() = settings.getBoolean("notification_play_while_app_open", true)
        set(value) = settings.putBoolean("notification_play_while_app_open", value)

    var errorCollectionEnabled: Boolean
        get() = settings.getBoolean("error_collection_enabled", true)
        set(value) = settings.putBoolean("error_collection_enabled", value)


    var notificationContentLevel: String
        get() = settings.getString("notification_content_level", "name_content_actions")
        set(value) = settings.putString("notification_content_level", value)

    var includeMutedChatsInBadge: Boolean
        get() = settings.getBoolean("notification_include_muted_badge", false)
        set(value) = settings.putBoolean("notification_include_muted_badge", value)

   
    fun getConversationScrollIndex(conversationId: String): Int? {
        return settings.getIntOrNull("conversationScrollIndex-$conversationId")
    }

    fun setConversationScrollIndex(conversationId: String, position: Int) {
        settings.putInt("conversationScrollIndex-$conversationId", position)
    }

    fun getConversationScrollOffset(conversationId: String): Int? {
        return settings.getIntOrNull("conversationScrollOffset-$conversationId")
    }

    fun setConversationScrollOffset(conversationId: String, position: Int) {
        settings.putInt("conversationScrollOffset-$conversationId", position)
    }
}

data class PreferenceState(
    val theme: ThemeState,
)

enum class ThemeState {
    System,
    Dark,
    Light,
}

expect fun createSettings(): Settings
