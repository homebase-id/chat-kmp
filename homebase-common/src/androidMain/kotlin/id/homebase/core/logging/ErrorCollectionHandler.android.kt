package id.homebase.core.logging

import com.google.firebase.crashlytics.FirebaseCrashlytics

actual fun setErrorCollectionEnabled(enabled: Boolean) {
    FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
}