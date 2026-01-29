package id.homebase.core.settings

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual fun createSettings(): Settings {
    val preferences = Preferences.userRoot().node("/id/homebase/app/window")
    val settings = PreferencesSettings(preferences)
    return settings
}