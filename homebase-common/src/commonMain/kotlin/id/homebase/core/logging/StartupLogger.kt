package id.homebase.core.logging

import co.touchlab.kermit.Logger

object StartupLogger {
    private const val TAG = "StartupLogger"

    fun logAppStartupInfo(versionName: String, versionCode: Int, buildDate: String) {
        Logger.i(tag = TAG) { "========== APP STARTUP ==========" }
        Logger.i(tag = TAG) { "Version: $versionName ($versionCode)" }
        Logger.i(tag = TAG) { "Build date: $buildDate" }
        Logger.i(tag = TAG) { "=================================" }
    }
}
