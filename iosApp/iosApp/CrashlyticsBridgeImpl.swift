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

    func recordException(name: String, reason: String, stackTrace: [String]) {
        let model = ExceptionModel(name: name, reason: reason)
        model.stackTrace = stackTrace.map { StackFrame(symbol: $0, file: "unknown", line: 0) }
        Crashlytics.crashlytics().record(exceptionModel: model)
    }
}
