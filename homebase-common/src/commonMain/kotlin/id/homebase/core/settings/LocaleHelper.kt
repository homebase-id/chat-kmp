package id.homebase.core.settings

/**
 * Get the current system locale language code (e.g., "en", "da")
 */
expect fun getSystemLocale(): String

/**
 * Sets the app's UI language to [languageCode], a [Language] code such as "da-DK", or "system" to follow the device.
 * Android applies it live; iOS stores it and needs an app restart; desktop sets the JVM default locale; web stores it and reloads the page.
 * Call from the main thread.
 */
expect fun setPlatformSystemLocale(languageCode: String)

fun applyStoredLocale(userPreferences: UserPreferences) {
    val savedLanguage = userPreferences.language
    setPlatformSystemLocale(savedLanguage)
}

/**
 * Supported languages in the app
 */
enum class Language(val code: String) {
    SYSTEM("system"),
    ENGLISH_US("en-US"),
    ENGLISH_GB("en-GB"),
    DANISH("da-DK");

    companion object {
        fun fromCode(code: String): Language {
            return entries.find { it.code == code } ?: SYSTEM
        }
    }
}
