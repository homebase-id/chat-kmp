import Foundation
import FirebaseCrashlytics
import ComposeApp

/// Swift implementation of CrashlyticsBridge that wraps Firebase Crashlytics.
/// This is injected into the Kotlin framework at app startup.
class CrashlyticsBridgeImpl: CrashlyticsBridge {

    func setCrashlyticsCollectionEnabled(enabled: Bool) {
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled)
    }

    func log(message: String) {
        Crashlytics.crashlytics().log(message)
    }

    func setCustomKey(key: String, value: String) {
        Crashlytics.crashlytics().setCustomValue(value, forKey: key)
    }
}
