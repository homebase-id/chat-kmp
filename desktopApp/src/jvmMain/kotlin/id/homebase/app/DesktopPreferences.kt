package id.homebase.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import java.util.prefs.Preferences

data class CallWindowGeometry(val width: Dp, val height: Dp, val position: WindowPosition)

class DesktopPreferences(settings: Settings? = null) {
    private val preferences: Settings by lazy {
        settings ?: PreferencesSettings(Preferences.userRoot().node("/id/homebase/app/window"))
    }

    fun callWindowGeometry(video: Boolean): CallWindowGeometry {
        val suffix = callWindowSuffix(video)
        val defaultSize = if (video) VIDEO_CALL_SIZE else VOICE_CALL_SIZE
        val position = when (preferences.getString("callWindowPosition$suffix", "")) {
            "Absolute" -> WindowPosition.Absolute(
                preferences.getFloat("callWindowPositionX$suffix", 0f).dp,
                preferences.getFloat("callWindowPositionY$suffix", 0f).dp,
            )
            "Aligned" -> WindowPosition.Aligned(Alignment.Center)
            else -> WindowPosition.PlatformDefault
        }
        return CallWindowGeometry(
            width = maxOf(preferences.getFloat("callWindowWidthDp$suffix", defaultSize.first).dp, CALL_WINDOW_MIN_WIDTH.dp),
            height = maxOf(preferences.getFloat("callWindowHeightDp$suffix", defaultSize.second).dp, CALL_WINDOW_MIN_HEIGHT.dp),
            position = position,
        )
    }

    fun saveCallWindowGeometry(video: Boolean, geometry: CallWindowGeometry) {
        val suffix = callWindowSuffix(video)
        preferences["callWindowWidthDp$suffix"] = geometry.width.value
        preferences["callWindowHeightDp$suffix"] = geometry.height.value
        when (val position = geometry.position) {
            is WindowPosition.Absolute -> {
                preferences["callWindowPosition$suffix"] = "Absolute"
                preferences["callWindowPositionX$suffix"] = position.x.value
                preferences["callWindowPositionY$suffix"] = position.y.value
            }
            is WindowPosition.Aligned -> preferences["callWindowPosition$suffix"] = "Aligned"
            is WindowPosition.PlatformDefault -> preferences["callWindowPosition$suffix"] = "PlatformDefault"
        }
    }

    fun miniWindowPosition(): WindowPosition.Absolute? {
        if (!preferences.hasKey("callMiniPositionX")) return null
        return WindowPosition.Absolute(preferences.getFloat("callMiniPositionX", 0f).dp, preferences.getFloat("callMiniPositionY", 0f).dp)
    }

    fun saveMiniWindowPosition(position: WindowPosition.Absolute) {
        preferences["callMiniPositionX"] = position.x.value
        preferences["callMiniPositionY"] = position.y.value
    }

    var callAutoMini: Boolean
        get() = preferences.getBoolean("callAutoMini", false)
        set(value) {
            preferences["callAutoMini"] = value
        }

    private fun callWindowSuffix(video: Boolean) = if (video) "Video" else "Voice"

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


const val CALL_WINDOW_MIN_WIDTH = 320f
const val CALL_WINDOW_MIN_HEIGHT = 240f
private val VIDEO_CALL_SIZE = 960f to 640f
private val VOICE_CALL_SIZE = 640f to 480f

val MINI_VIDEO_SIZE = DpSize(320.dp, 180.dp)
val MINI_VOICE_SIZE = DpSize(360.dp, 88.dp)
private val MINI_SCREEN_MARGIN = 24.dp

fun miniCallSize(video: Boolean): DpSize = if (video) MINI_VIDEO_SIZE else MINI_VOICE_SIZE

fun miniCallPosition(saved: WindowPosition.Absolute?, size: DpSize, screens: List<DpRect>): WindowPosition.Absolute {
    val home = saved?.let { at ->
        val cx = at.x + size.width / 2
        val cy = at.y + size.height / 2
        screens.firstOrNull { cx >= it.left && cx < it.right && cy >= it.top && cy < it.bottom }
    }
    if (saved != null && home != null) {
        return WindowPosition.Absolute(
            saved.x.coerceIn(home.left, maxOf(home.left, home.right - size.width)),
            saved.y.coerceIn(home.top, maxOf(home.top, home.bottom - size.height)),
        )
    }
    val primary = screens.firstOrNull() ?: DpRect(0.dp, 0.dp, 1440.dp, 900.dp)
    // Top end, clear of the menu bar: the bottom corner is where a chat window keeps its composer and send button.
    return WindowPosition.Absolute(
        maxOf(primary.left, primary.right - size.width - MINI_SCREEN_MARGIN),
        minOf(primary.top + MINI_SCREEN_MARGIN * 3, maxOf(primary.top, primary.bottom - size.height)),
    )
}
