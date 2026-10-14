package id.homebase.chat.services.calling

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

class SettingsCallDevicePreferences(private val settings: Settings) : CallDevicePreferences {
    override fun get(kind: MediaDeviceKind): String? = settings.getStringOrNull(key(kind))
    override fun set(kind: MediaDeviceKind, id: String) = settings.putString(key(kind), id)
    private fun key(kind: MediaDeviceKind) = "call_device_${kind.name.lowercase()}"
}

internal fun desktopCallDevicePreferences(): CallDevicePreferences =
    SettingsCallDevicePreferences(PreferencesSettings(Preferences.userRoot().node("id/homebase/chat/call")))
