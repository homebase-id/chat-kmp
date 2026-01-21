package id.homebase.core.settings

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual fun createSettings(): Settings {
    return PreferencesSettings(
        Preferences.userRoot().node("id.homebase.app")
    )
}