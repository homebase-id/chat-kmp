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

    /**
     * One-shot timing marker. The log timestamp is the data; diff two checkpoints
     * to size a phase. Cheap — fire-and-forget Logger.i, no allocation beyond the
     * literal. Add at hand-picked boundaries (Koin built, DB open, Compose entered,
     * top-level VM resolved). Don't call from a Composable body — wrap in
     * LaunchedEffect(Unit) so it fires once, not per recomposition.
     */
    fun checkpoint(name: String) {
        Logger.i(tag = TAG) { "checkpoint: $name" }
    }
}
