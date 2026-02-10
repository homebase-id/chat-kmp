package id.homebase.core.settings

import com.russhwolf.settings.Settings

class UserPreferences(private val settings: Settings) {
    var language: String
        get() = settings.getString("language", "system")
        set(value) = settings.putString("language", value)

    // Notification preferences
    var playWhileAppOpen: Boolean
        get() = settings.getBoolean("notification_play_while_app_open", true)
        set(value) = settings.putBoolean("notification_play_while_app_open", value)

    var notificationContentLevel: String
        get() = settings.getString("notification_content_level", "name_content_actions")
        set(value) = settings.putString("notification_content_level", value)

    var includeMutedChatsInBadge: Boolean
        get() = settings.getBoolean("notification_include_muted_badge", false)
        set(value) = settings.putBoolean("notification_include_muted_badge", value)

    var notifyOnContactJoins: Boolean
        get() = settings.getBoolean("notification_contact_joins", false)
        set(value) = settings.putBoolean("notification_contact_joins", value)

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

expect fun createSettings(): Settings
