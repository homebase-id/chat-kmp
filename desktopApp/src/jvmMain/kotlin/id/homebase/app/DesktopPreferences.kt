package id.homebase.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import java.util.prefs.Preferences

class DesktopPreferences {
    private val preferences: Settings by lazy {
        val preferences = Preferences.userRoot().node("/id/homebase/app/window")
        val settings = PreferencesSettings(preferences)
        settings
    }

    var windowWidthDp: Dp
        get() {
            return preferences.getFloat("windowWidthDp", 1200f).dp
        }
        set(value) {
            preferences["windowWidthDp"] = value.value
        }
    var windowHeightDp: Dp
        get() {
            return preferences.getFloat("windowHeightDp", 740f).dp
        }
        set(value) {
            preferences["windowHeightDp"] = value.value
        }
    var windowPosition: WindowPosition
        get() {
            try {
                val position = preferences.getString("windowPosition", "PlatformDefault")
                return when (position) {
                    "Absolute" -> {
                        WindowPosition.Absolute(Dp(preferences.getFloat("windowPositionX", 0f)), Dp(preferences.getFloat("windowPositionY", 0f)))
                    }

                    "Aligned" -> {
                        WindowPosition.Aligned(Alignment.Center)
                    }

                    else -> {
                        WindowPosition.PlatformDefault
                    }
                }
            } catch (_: Exception) {
                return WindowPosition.PlatformDefault
            }
        }
        set(value) {
            when (value) {
                is WindowPosition.Absolute -> {
                    preferences["windowPosition"] = "Absolute"
                    preferences["windowPositionX"] = value.x.value
                    preferences["windowPositionY"] = value.y.value
                }
                is WindowPosition.Aligned -> {
                    preferences["windowPosition"] = "Aligned"
                }
                is WindowPosition.PlatformDefault -> {
                    preferences["windowPosition"] = "PlatformDefault"
                }
            }
        }
    var windowPlacement: WindowPlacement
        get() {
            return WindowPlacement.valueOf(preferences.getString("windowPlacement", WindowPlacement.Floating.toString()))
        }
        set(value) {
            preferences["windowPlacement"] = value.toString()
        }
}

