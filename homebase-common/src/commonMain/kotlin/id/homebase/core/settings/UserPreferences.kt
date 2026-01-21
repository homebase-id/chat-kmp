package id.homebase.core.settings

import com.russhwolf.settings.Settings

class UserPreferences(private val settings: Settings) {
    var language: String
        get() = settings.getString("language", "system")
        set(value) = settings.putString("language", value)
}

expect fun createSettings(): Settings