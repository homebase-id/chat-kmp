package id.homebase.core.settings

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual fun createSettings(): Settings {
    error("Use createSettings(Context) instead on Android")
}

// Android-specific function that accepts Context
fun createSettings(context: Context): Settings {
    val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(sharedPreferences)
}
