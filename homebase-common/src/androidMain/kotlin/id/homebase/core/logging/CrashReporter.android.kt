package id.homebase.core.logging

import com.google.firebase.crashlytics.FirebaseCrashlytics

actual fun crashlyticsLog(message: String) {
    FirebaseCrashlytics.getInstance().log(message)
}

actual fun crashlyticsRecordException(throwable: Throwable) {
    FirebaseCrashlytics.getInstance().recordException(throwable)
}
