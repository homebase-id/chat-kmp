package id.homebase.core.settings

import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

actual fun getSystemLocale(): String {
    return Resources.getSystem().configuration.locales[0].language
}

actual fun setPlatformSystemLocale(languageCode: String) {
    Log.d("LocaleConfig", "Setting locale to: $languageCode")

    val locale = when (languageCode) {
        Language.SYSTEM.code -> {
            Log.d("LocaleConfig", "Using system default")
            LocaleListCompat.getEmptyLocaleList()
        }
        else -> {
            Log.d("LocaleConfig", "Using language tag: $languageCode")
            LocaleListCompat.forLanguageTags(languageCode)
        }
    }

    AppCompatDelegate.setApplicationLocales(locale)

    val currentLocales = AppCompatDelegate.getApplicationLocales()
    Log.d("LocaleConfig", "Current locales after setting: ${currentLocales.toLanguageTags()}")
}