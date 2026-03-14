package id.homebase.core.logging

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSSetUncaughtExceptionHandler

private const val TAG = "IOSCrashHandler"

/**
 * Sets up uncaught exception handler for iOS
 */
@OptIn(ExperimentalForeignApi::class)
fun setupIOSCrashHandler() {
    NSSetUncaughtExceptionHandler(staticCFunction { exception ->
        exception?.let {
            try {
                Logger.e(tag = TAG) { "========== UNCAUGHT EXCEPTION ==========" }
                Logger.e(tag = TAG) { "Name: ${it.name}" }
                Logger.e(tag = TAG) { "Reason: ${it.reason}" }
                Logger.e(tag = TAG) { "Call Stack: ${it.callStackSymbols}" }
                Logger.e(tag = TAG) { "========================================" }

                // Give logs time to flush
                platform.Foundation.NSThread.sleepForTimeInterval(0.1)
            } catch (e: Exception) {
                println("IOSCrashHandler failed: ${e.message}")
            }
        }
    })
}