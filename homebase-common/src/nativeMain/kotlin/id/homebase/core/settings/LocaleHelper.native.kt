package id.homebase.core.settings

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

actual fun getSystemLocale(): String {
    return NSLocale.currentLocale.languageCode
}

actual fun setPlatformSystemLocale(languageCode: String) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (languageCode == "system") {
        defaults.removeObjectForKey("AppleLanguages")
    } else {
        defaults.setObject(listOf(languageCode), forKey = "AppleLanguages")
    }
    defaults.synchronize()
    // Note: On iOS, app restart is required for locale changes to take full effect
}