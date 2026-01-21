package id.homebase.core.settings

@OptIn(ExperimentalWasmJsInterop::class)
actual fun getSystemLocale(): String {
    // Try to get browser language
    return try {
        js("navigator.language || navigator.userLanguage || 'en'") as String
    } catch (_: Exception) {
        "en"
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
actual fun setPlatformSystemLocale(languageCode: String) {
    // For web, we store in localStorage and reload the page
    if (languageCode == "system") {
        js("localStorage.removeItem('app_locale')")
    } else {
        js("localStorage.setItem('app_locale', '$languageCode')")
    }
    // Reload to apply changes
    js("window.location.reload()")
}