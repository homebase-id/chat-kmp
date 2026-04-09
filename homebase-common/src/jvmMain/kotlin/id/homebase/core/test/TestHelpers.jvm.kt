package id.homebase.core.test

import java.util.Locale

actual fun setTestLocale(languageTag: String) {
    Locale.setDefault(Locale.forLanguageTag(languageTag))
}
