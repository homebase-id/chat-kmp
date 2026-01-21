package id.homebase.core.settings

import java.util.Locale

actual fun getSystemLocale(): String {
    return Locale.getDefault().language
}

actual fun setPlatformSystemLocale(languageCode: String) {
    val locale = when (languageCode) {
        "system" -> Locale.getDefault()
        else -> Locale.forLanguageTag(languageCode)
    }
    Locale.setDefault(locale)
}