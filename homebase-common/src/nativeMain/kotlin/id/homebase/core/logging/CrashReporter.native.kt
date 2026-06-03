package id.homebase.core.logging

import kotlin.experimental.ExperimentalNativeApi

actual fun crashlyticsLog(message: String) {
    CrashlyticsBridgeHolder.getBridge()?.log(message)
}

@OptIn(ExperimentalNativeApi::class)
actual fun crashlyticsRecordException(throwable: Throwable) {
    val bridge = CrashlyticsBridgeHolder.getBridge() ?: return
    bridge.recordException(
        name = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "KotlinException",
        reason = throwable.message ?: throwable.toString(),
        stackTrace = throwable.getStackTrace().toList(),
    )
}
