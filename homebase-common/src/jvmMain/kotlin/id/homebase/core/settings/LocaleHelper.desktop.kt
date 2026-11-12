package id.homebase.core.settings

import java.util.Locale

private var startupLocale: Locale = Locale.getDefault()

internal fun resetStartupLocaleForTest() {
    startupLocale = Locale.getDefault()
}

actual fun getSystemLocale(): String {
    return startupLocale.language
}

actual fun setPlatformSystemLocale(languageCode: String) {
    val locale = when (languageCode) {
        "system" -> startupLocale
        else -> Locale.forLanguageTag(languageCode)
    }
    Locale.setDefault(locale)
}
