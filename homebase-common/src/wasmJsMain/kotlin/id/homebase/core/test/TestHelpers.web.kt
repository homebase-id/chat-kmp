package id.homebase.core.test

actual fun setTestLocale(languageTag: String) {
    // No-op on web; test infrastructure for wasmJs is not in place yet.
}
