package id.homebase.core.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

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

    var showDeveloperMenu: Boolean
        get() = settings.getBoolean("show_developer_menu", false)
        set(value) = settings.putBoolean("show_developer_menu", value)

    /**
     * Developer/test escape hatch (default off). When on, uploaded 10-bit
     * videos keep their 10-bit depth (High 10) instead of being downconverted
     * to 8-bit `yuv420p`. High 10 output fails on most receivers' hardware AVC
     * decoders, so this is for locally inspecting the 10-bit pipeline only —
     * not a shippable default. See FfmpegCompressPlanner.plan's `allowTenBit`.
     */
    var allowTenBitVideo: Boolean
        get() = settings.getBoolean("allow_ten_bit_video", false)
        set(value) = settings.putBoolean("allow_ten_bit_video", value)

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

   
    /**
     * Per-conversation scroll anchor — the uniqueId of the message the user
     * was looking at. Resolved to a list index against the freshly-loaded
     * messages on next open. Stored as a string (UUID) under
     * `conversationScrollAnchor-<conversationId>`.
     *
     * Replaces the older `conversationScrollIndex-*` int key, which was
     * meaningless across sessions because new messages between sessions
     * shift indices. The old key is abandoned with no migration: the first
     * open after upgrade falls through to "land at bottom" once.
     */
    fun getConversationScrollAnchor(conversationId: String): Uuid? {
        val raw = settings.getStringOrNull("conversationScrollAnchor-$conversationId")
            ?: return null
        return try {
            Uuid.parse(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun setConversationScrollAnchor(conversationId: String, anchor: Uuid?) {
        val key = "conversationScrollAnchor-$conversationId"
        if (anchor == null) {
            settings.remove(key)
        } else {
            settings.putString(key, anchor.toString())
        }
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
