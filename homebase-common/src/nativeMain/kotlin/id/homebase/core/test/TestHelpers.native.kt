package id.homebase.core.test

import platform.Foundation.NSUserDefaults

actual fun setTestLocale(languageTag: String) {
    val defaults = NSUserDefaults.standardUserDefaults
    defaults.setObject(listOf(languageTag), forKey = "AppleLanguages")
    defaults.synchronize()
}