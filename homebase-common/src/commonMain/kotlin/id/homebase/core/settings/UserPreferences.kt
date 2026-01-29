package id.homebase.core.settings

import com.russhwolf.settings.Settings

class UserPreferences(private val settings: Settings) {
    var language: String
        get() = settings.getString("language", "system")
        set(value) = settings.putString("language", value)

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