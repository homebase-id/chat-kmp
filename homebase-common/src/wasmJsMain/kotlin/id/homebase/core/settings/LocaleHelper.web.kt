package id.homebase.core.settings

actual fun getSystemLocale(): String = js("navigator.language || navigator.userLanguage || 'en'")

actual fun setPlatformSystemLocale(languageCode: String): Unit = js(
    """{
        if (languageCode === 'system') {
            localStorage.removeItem('app_locale');
            window.location.reload();
            return
        }
        localStorage.setItem('app_locale', languageCode);
        window.location.reload();
}"""
)
